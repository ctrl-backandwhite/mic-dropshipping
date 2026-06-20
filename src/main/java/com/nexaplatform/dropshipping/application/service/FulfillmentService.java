package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoFulfillmentService.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orquesta el fulfillment con Cainiao sobre el modelo de pedido: crea el envío al despachar, mantiene el
 * timeline de eventos de seguimiento y deriva el estado del envío. NO cambia el {@code OrderStatus} en el
 * sondeo (eso lo hace el scheduler vía las transiciones del use case, para mantener emails/webhooks);
 * aquí solo se persisten los eventos y se calcula el estado objetivo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private final OrderRepository orderRepository;
    private final OrderTrackingEventRepository trackingRepository;
    private final CainiaoFulfillmentService cainiao;
    private final UserRepository userRepository;
    private final OrderEmailService orderEmailService;
    private final ObjectMapper objectMapper;

    /** Estado actual del pedido + estado objetivo del envío tras sondear el tracking. */
    public record TrackingProgress(OrderStatus current, OrderStatus target) {
    }

    /** Ids de pedidos con envío activo (FORWARDED/SHIPPED) — leído en transacción para no perder la sesión. */
    @Transactional(readOnly = true)
    public List<UUID> activeOrderIds() {
        return orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.FORWARDED || o.getStatus() == OrderStatus.SHIPPED)
                .map(Order::getId).toList();
    }

    /** Al despachar el pedido: crea el envío en Cainiao y registra el primer evento del timeline. */
    @Transactional
    public void createShipment(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null || o.getTrackingNumber() != null || o.getStatus() != OrderStatus.FORWARDED) {
            return; // sin pedido, ya tiene envío, o aún no despachado
        }
        FulfillmentResult r = cainiao.createShipment(o);
        o.setCarrier(r.carrier());
        o.setTrackingNumber(r.trackingNumber());
        o.setFulfillmentRef(r.fulfillmentRef());
        o.setTrackingStatus(OrderStatus.FORWARDED.name());
        o.setEstimatedDeliveryAt(Instant.now().plus(Duration.ofDays(r.etaMaxDays())));
        o.setLastTrackedAt(Instant.now());
        orderRepository.save(o);
        appendEvent(o.getId(), OrderStatus.FORWARDED.name(), "Envío registrado en Cainiao", "Shenzhen, CN", "SYSTEM",
                o.getForwardedAt() != null ? o.getForwardedAt() : Instant.now());
        log.info("Cainiao: envío {} creado para pedido {}", r.trackingNumber(), o.getOrderNumber());
    }

    /**
     * Sondea el tracking en Cainiao, añade los eventos nuevos al timeline, actualiza el último estado y
     * devuelve el estado actual + objetivo del envío (FORWARDED/SHIPPED/DELIVERED). No cambia
     * {@code OrderStatus} (lo hace el scheduler vía las transiciones del use case).
     */
    @Transactional
    public TrackingProgress pollEvents(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null) {
            return new TrackingProgress(null, null);
        }
        if (o.getTrackingNumber() == null) {
            return new TrackingProgress(o.getStatus(), o.getStatus());
        }
        TrackingSnapshot snap = cainiao.track(o.getTrackingNumber(), o.getForwardedAt(), o.getShippingCountry());
        List<OrderTrackingEventEntity> existing = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId());
        Set<String> seen = new HashSet<>();
        for (OrderTrackingEventEntity e : existing) {
            seen.add(e.getStatus() + "|" + e.getDescription());
        }
        // Notificación por cada cambio de estado del envío. El estado interno FORWARDED ("registrado en
        // Cainiao") NO se notifica. El PRIMER paso SHIPPED ("Recogido por el transportista") y el paso
        // DELIVERED los notifican shipped()/delivered() en el use case al avanzar el OrderStatus, así que
        // aquí solo notificamos los pasos intermedios SHIPPED (en tránsito, llegó al país, en reparto) para
        // no duplicar.
        boolean shippedSeen = existing.stream().anyMatch(e -> OrderStatus.SHIPPED.name().equals(e.getStatus()));
        List<TrackingStep> toNotify = new ArrayList<>();
        for (TrackingStep step : snap.steps()) {
            String key = step.status().name() + "|" + step.description();
            if (seen.add(key)) {
                appendEvent(o.getId(), step.status().name(), step.description(), step.location(), "CAINIAO",
                        step.occurredAt());
                if (step.status() == OrderStatus.SHIPPED) {
                    if (shippedSeen) {
                        toNotify.add(step); // paso intermedio → notificar
                    } else {
                        shippedSeen = true; // primer SHIPPED = "Recogido" → lo cubre shipped()
                    }
                }
            }
        }
        o.setLastTrackedAt(Instant.now());
        o.setTrackingStatus(snap.currentStatus().name());
        OrderStatus current = o.getStatus();
        orderRepository.save(o);
        notifyTrackingSteps(o, toNotify);
        return new TrackingProgress(current, snap.currentStatus());
    }

    /** Envía un email por cada paso intermedio del envío al comprador (resuelve email/idioma del usuario). */
    private void notifyTrackingSteps(Order o, List<TrackingStep> steps) {
        if (steps.isEmpty() || o.getUserId() == null) {
            return;
        }
        User u = userRepository.getById(o.getUserId());
        if (u == null) {
            return;
        }
        steps.forEach(s -> orderEmailService.trackingUpdate(o, u.getEmail(), u.getLanguage(), s.description(),
                s.location()));
    }

    /** Timeline de eventos de un pedido (orden cronológico). */
    @Transactional(readOnly = true)
    public List<OrderTrackingEventEntity> timeline(UUID orderId) {
        return trackingRepository.findByOrderIdOrderByOccurredAtAsc(orderId);
    }

    /** Vista de seguimiento (resumen del envío + timeline). Solo lee columnas simples, nunca items. */
    public record TrackingEventView(String status, String description, String location, String source,
            Instant occurredAt) {
    }

    public record TrackingView(String status, String carrier, String trackingNumber, Instant estimatedDeliveryAt,
            Instant lastTrackedAt, List<TrackingEventView> events) {
    }

    /** Vista de tracking del pedido del usuario (valida propiedad). */
    @Transactional(readOnly = true)
    public TrackingView myTrackingView(UUID userId, UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        if (o.getUserId() == null || !o.getUserId().equals(userId)) {
            throw new NotFoundException("Order");
        }
        return view(o);
    }

    /** Vista de tracking para el admin. */
    @Transactional(readOnly = true)
    public TrackingView adminTrackingView(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        return view(o);
    }

    private TrackingView view(Order o) {
        List<TrackingEventView> events = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId()).stream()
                .map(e -> new TrackingEventView(e.getStatus(), e.getDescription(), e.getLocation(), e.getSource(),
                        e.getOccurredAt()))
                .toList();
        return new TrackingView(o.getStatus() != null ? o.getStatus().name() : null, o.getCarrier(),
                o.getTrackingNumber(), o.getEstimatedDeliveryAt(), o.getLastTrackedAt(), events);
    }

    /** Registra un evento del timeline a mano (p.ej. desde el admin). */
    @Transactional
    public void appendEvent(UUID orderId, String status, String description, String location, String source,
            Instant occurredAt) {
        trackingRepository.save(OrderTrackingEventEntity.builder().orderId(orderId).status(status)
                .description(description).location(location).source(source)
                .occurredAt(occurredAt != null ? occurredAt : Instant.now()).createdAt(Instant.now()).build());
    }

    // ─────────────────────── Fase 2: push entrante de Cainiao (webhooks) ───────────────────────

    /**
     * Aplica un push de Cainiao ({@code TRACEPUSH} o {@code CAINIAO_GLOBAL_FULFILL_STATUS_SYNC}) al timeline
     * del pedido. La firma ya la valida el controller; aquí solo se resuelve el pedido y se añaden los
     * eventos NUEVOS (dedup por estado+descripción, igual que el sondeo).
     *
     * <p><b>TODO(real)</b>: ajustar los nombres de campo del payload (mailNo/orderCode, array de trazas,
     * códigos de acción→OrderStatus) cuando tengamos el detalle exacto de cada API. De momento prueba los
     * nombres más habituales; lo que no reconoce, lo registra en log para mapearlo.
     */
    @Transactional
    public void applyPush(String msgType, String logisticsInterface) {
        JsonNode root;
        try {
            root = objectMapper.readTree(logisticsInterface);
        } catch (JsonProcessingException e) {
            log.warn("Cainiao push {}: JSON inválido", msgType);
            return;
        }
        Order o = resolvePushOrder(root);
        if (o == null) {
            log.warn("Cainiao push {}: pedido no encontrado en payload {}", msgType, logisticsInterface);
            return;
        }
        JsonNode events = firstArrayNode(root, "traceDetailList", "traces", "detailList", "actionList");
        boolean changed = false;
        if (events != null && events.isArray() && !events.isEmpty()) {
            for (JsonNode ev : events) {
                changed |= appendIfNew(o, mapPushStatus(firstNodeText(ev, "action", "status", "logisticsStatus")),
                        firstNodeText(ev, "desc", "standerdDesc", "remark", "statusDesc"),
                        firstNodeText(ev, "city", "location"), pushInstant(ev));
            }
        } else {
            // FULFILL_STATUS_SYNC y otros de un solo estado.
            String desc = firstNodeText(root, "statusDesc", "logisticsStatusDesc", "desc", "action");
            if (desc != null) {
                changed = appendIfNew(o, mapPushStatus(firstNodeText(root, "logisticsStatus", "status", "action")),
                        desc, firstNodeText(root, "city", "location"), pushInstant(root));
            }
        }
        if (changed) {
            o.setLastTrackedAt(Instant.now());
            orderRepository.save(o);
            log.info("Cainiao push {}: timeline actualizado para pedido {}", msgType, o.getOrderNumber());
        }
    }

    /** Resuelve el pedido del push por mailNo (tracking) u orderCode (orderNumber). */
    private Order resolvePushOrder(JsonNode root) {
        String mailNo = firstNodeText(root, "mailNo", "trackingNumber", "waybillCode", "lpCode");
        if (mailNo != null) {
            Order o = orderRepository.findByTrackingNumber(mailNo).orElse(null);
            if (o != null) {
                return o;
            }
        }
        String orderCode = firstNodeText(root, "orderCode", "tradeOrderId", "outOrderId");
        return orderCode != null ? orderRepository.findByOrderNumber(orderCode).orElse(null) : null;
    }

    /** Añade el evento si no existe ya (dedup por estado|descripción). Devuelve true si lo añadió. */
    private boolean appendIfNew(Order o, OrderStatus status, String desc, String location, Instant when) {
        if (desc == null || desc.isBlank()) {
            return false;
        }
        String key = status.name() + "|" + desc;
        boolean exists = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId()).stream()
                .anyMatch(e -> key.equals(e.getStatus() + "|" + e.getDescription()));
        if (exists) {
            return false;
        }
        appendEvent(o.getId(), status.name(), desc, location, "CAINIAO", when);
        o.setTrackingStatus(status.name());
        return true;
    }

    /** Mapea el texto/código de estado de Cainiao a {@link OrderStatus}. TODO(real): mapa por código exacto. */
    private static OrderStatus mapPushStatus(String raw) {
        if (raw == null) {
            return OrderStatus.SHIPPED;
        }
        String s = raw.toUpperCase();
        if (s.contains("DELIVER") || s.contains("SIGN") || s.contains("SIGNED")) {
            return OrderStatus.DELIVERED;
        }
        if (s.contains("ACCEPT") || s.contains("CREATE") || s.contains("REGIST")) {
            return OrderStatus.FORWARDED;
        }
        return OrderStatus.SHIPPED;
    }

    private static JsonNode firstArrayNode(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private static String firstNodeText(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText(null);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Instant pushInstant(JsonNode node) {
        long ts = node.path("time").asLong(node.path("occurTime").asLong(node.path("gmtModified").asLong(0L)));
        return ts > 0 ? Instant.ofEpochMilli(ts) : Instant.now();
    }
}
