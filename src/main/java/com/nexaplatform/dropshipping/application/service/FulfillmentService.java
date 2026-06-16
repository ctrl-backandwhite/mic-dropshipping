package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
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
        for (TrackingStep step : snap.steps()) {
            String key = step.status().name() + "|" + step.description();
            if (seen.add(key)) {
                appendEvent(o.getId(), step.status().name(), step.description(), step.location(), "CAINIAO",
                        step.occurredAt());
            }
        }
        o.setLastTrackedAt(Instant.now());
        o.setTrackingStatus(snap.currentStatus().name());
        OrderStatus current = o.getStatus();
        orderRepository.save(o);
        return new TrackingProgress(current, snap.currentStatus());
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
}
