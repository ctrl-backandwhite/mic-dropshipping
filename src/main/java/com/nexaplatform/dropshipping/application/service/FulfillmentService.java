package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CarrierErrorMessage;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentFailure;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orquesta el fulfillment con el proveedor activo (YunExpress) sobre el modelo de pedido: crea el envío
 * al despachar, mantiene el timeline de eventos de seguimiento y deriva el estado del envío. NO cambia el {@code OrderStatus} en el
 * sondeo (eso lo hace el scheduler vía las transiciones del use case, para mantener emails/webhooks);
 * aquí solo se persisten los eventos y se calcula el estado objetivo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String ORDER = "Order";

    /**
     * Origen que se graba en los eventos que produce el SONDEO del proveedor activo. Los pushes entrantes
     * escriben el suyo propio (`YUNEXPRESS` / `CAINIAO`), así que el timeline distingue de dónde vino cada
     * paso: sondeo del carrier, push del carrier o alta manual del admin.
     */
    private static final String CARRIER_SOURCE = "YUNEXPRESS";

    /**
     * Intentos de creación del envío antes de dejarlo en manos del admin. Tres intentos separados por
     * {@link #RETRY_DELAY_MINUTES} cubren media hora de indisponibilidad del transportista; más allá de
     * eso ya no es un bache pasajero y hace falta que alguien lo mire.
     */
    private static final int MAX_FULFILLMENT_ATTEMPTS = 3;
    /** Espera fija entre intentos. */
    private static final long RETRY_DELAY_MINUTES = 10L;

    private final OrderRepository orderRepository;
    private final OrderTrackingEventRepository trackingRepository;
    private final FulfillmentProvider fulfillment;
    private final UserRepository userRepository;
    private final OrderEmailService orderEmailService;
    private final ObjectMapper objectMapper;
    /** Verificación de firma y descifrado de los pushes de YunExpress (事件管理). */
    private final YunExpressEventCipher eventCipher;
    /** Avisos por correo cuando el transportista deja de aceptar envíos. */
    private final OpsAlertService opsAlertService;
    /** Bandeja de entrada del panel, para que la incidencia no dependa de que alguien lea el correo. */
    private final NotificationUseCase notificationUseCase;
    /** Bultos del pedido: un pedido puede viajar en varias guías. */
    private final OrderShipmentRepository shipmentRepository;
    /** Proyección de entidades de seguimiento a las vistas de la API (MapStruct). */
    private final TrackingViewMapper trackingViewMapper;

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

    /**
     * Al despachar el pedido: crea el envío en el carrier y registra el primer evento del timeline.
     *
     * <p>Si el transportista falla, el pedido NO se queda reintentando en silencio: el motivo se guarda
     * en el propio pedido y el siguiente intento se espacia. Un fallo permanente —el bulto no cabe en el
     * canal, la declaración es inválida— se abandona al primer intento, porque reintentarlo cada minuto
     * no lo arregla y solo retrasa que alguien lo mire.
     */
    @Transactional
    public void createShipment(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null || o.getTrackingNumber() != null || o.getStatus() != OrderStatus.FORWARDED) {
            return; // sin pedido, ya tiene envío, o aún no despachado
        }
        if (!readyForAttempt(o)) {
            return; // rendido, o aún dentro de la espera del backoff
        }
        List<FulfillmentResult> results;
        try {
            results = fulfillment.createShipments(o);
        } catch (RuntimeException e) {
            recordFailure(o, FulfillmentFailure.of(e));
            return;
        }
        if (results.isEmpty()) {
            recordFailure(o, new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT,
                    "El transportista no devolvió ningún envío para el pedido " + o.getOrderNumber()));
            return;
        }
        persistShipments(o, results);
        // El pedido conserva los datos del PRIMER bulto por compatibilidad (listados, correos, factura);
        // el detalle paquete a paquete vive en order_shipment.
        FulfillmentResult r = results.get(0);
        o.setCarrier(r.carrier());
        o.setTrackingNumber(r.trackingNumber());
        o.setFulfillmentRef(r.fulfillmentRef());
        o.setTrackingStatus(OrderStatus.FORWARDED.name());
        o.setEstimatedDeliveryAt(Instant.now().plus(Duration.ofDays(r.etaMaxDays())));
        o.setLastTrackedAt(Instant.now());
        // La guía existe: se borra el rastro de los intentos fallidos para no dejar un error caducado
        // a la vista del admin.
        o.setFulfillmentAttempts(0);
        o.setFulfillmentError(null);
        o.setFulfillmentFailedAt(null);
        o.setFulfillmentNextAttemptAt(null);
        orderRepository.save(o);
        appendEvent(o.getId(), OrderStatus.FORWARDED.name(), "Envío registrado para entrega", "Shenzhen, CN", "SYSTEM",
                o.getForwardedAt() != null ? o.getForwardedAt() : Instant.now());
        log.info("Fulfillment: envío {} creado para pedido {}", r.trackingNumber(), o.getOrderNumber());
    }

    /** Da de alta en {@code order_shipment} cada bulto creado en el transportista. */
    private void persistShipments(Order o, List<FulfillmentResult> results) {
        for (FulfillmentResult r : results) {
            shipmentRepository.save(OrderShipmentEntity.builder()
                    .orderId(o.getId()).sequenceNo(r.sequenceNo()).carrier(r.carrier())
                    .productCode(r.productCode()).waybillNumber(r.fulfillmentRef())
                    .trackingNumber(r.trackingNumber()).status(OrderStatus.FORWARDED.name())
                    .weightGrams(r.weightGrams()).declaredValueCents(r.declaredValueCents())
                    .estimatedDeliveryAt(Instant.now().plus(Duration.ofDays(r.etaMaxDays())))
                    .createdAt(Instant.now()).build());
        }
        if (results.size() > 1) {
            log.info("::> [FULFILLMENT] Pedido {} despachado en {} bultos", o.getOrderNumber(), results.size());
        }
    }

    /** Bultos del pedido, en orden. */
    @Transactional(readOnly = true)
    public List<OrderShipmentEntity> shipmentsOf(UUID orderId) {
        return shipmentRepository.findByOrderIdOrderBySequenceNoAsc(orderId);
    }

    /** ¿Toca intentarlo? No si ya se dio por perdido ni si aún no ha vencido la espera del backoff. */
    private boolean readyForAttempt(Order o) {
        if (o.getFulfillmentFailedAt() != null) {
            return false;
        }
        return o.getFulfillmentNextAttemptAt() == null || !Instant.now().isBefore(o.getFulfillmentNextAttemptAt());
    }

    /**
     * Anota el fallo en el pedido y decide si habrá otro intento. La espera crece exponencialmente
     * ({@code 2^intentos} minutos, con techo) para no martillear al transportista cuando está caído, y
     * tras {@link #MAX_FULFILLMENT_ATTEMPTS} intentos se abandona: a esas alturas ya no es un problema
     * pasajero y hace falta que alguien intervenga.
     */
    private void recordFailure(Order o, FulfillmentFailure failure) {
        int attempts = o.getFulfillmentAttempts() + 1;
        o.setFulfillmentAttempts(attempts);
        o.setFulfillmentError(failure.getMessage());
        boolean giveUp = failure.isPermanent() || attempts >= MAX_FULFILLMENT_ATTEMPTS;
        if (giveUp) {
            o.setFulfillmentFailedAt(Instant.now());
            o.setFulfillmentNextAttemptAt(null);
            log.error("::> [FULFILLMENT] Envío abandonado pedido={} intentos={} motivo={} causa={}",
                    o.getOrderNumber(), attempts, failure.kind(), failure.getMessage());
            alertFulfillmentGiveUp(o, attempts, failure);
        } else {
            o.setFulfillmentNextAttemptAt(Instant.now().plus(Duration.ofMinutes(RETRY_DELAY_MINUTES)));
            log.warn("::> [FULFILLMENT] Envío falló pedido={} intento={}/{} reintento en {} min causa={}",
                    o.getOrderNumber(), attempts, MAX_FULFILLMENT_ATTEMPTS, RETRY_DELAY_MINUTES,
                    failure.getMessage());
        }
        orderRepository.save(o);
    }

    /**
     * Avisa al responsable de que el envío se ha dado por perdido: correo y bandeja de entrada del panel.
     * Nunca deja que un problema al notificar tumbe el flujo — el fallo del envío ya está persistido.
     */
    private void alertFulfillmentGiveUp(Order o, int attempts, FulfillmentFailure failure) {
        try {
            opsAlertService.fulfillmentFailed(o.getOrderNumber(), o.getShippingCountry(), attempts,
                    failure.getMessage());
            notificationUseCase.sendAdminNotification(null,
                    "Envío no creado: pedido " + o.getOrderNumber(),
                    "Tras " + attempts + " intento(s) el transportista sigue rechazando el envío a "
                            + o.getShippingCountry() + ". "
                            + CarrierErrorMessage.humanize(failure.getMessage())
                            + " Revisa la incidencia y reintenta cuando esté corregido.");
        } catch (RuntimeException e) {
            log.error("::> [FULFILLMENT] No se pudo avisar del envío abandonado pedido={} causa={}",
                    o.getOrderNumber(), e.getMessage());
        }
    }

    /** Pedidos cuyo envío se abandonó y esperan intervención manual (bandeja de incidencias del admin). */
    @Transactional(readOnly = true)
    public List<Order> failedFulfillments() {
        return orderRepository.findAll().stream()
                .filter(o -> o.getFulfillmentFailedAt() != null && o.getTrackingNumber() == null)
                .sorted((a, b) -> b.getFulfillmentFailedAt().compareTo(a.getFulfillmentFailedAt()))
                .toList();
    }

    /**
     * Rehabilita el envío de un pedido abandonado para que el scheduler vuelva a intentarlo. Se usa desde
     * el admin después de corregir lo que lo bloqueaba (canal, peso, declaración).
     */
    @Transactional
    public void retryFulfillment(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException(ORDER));
        o.setFulfillmentFailedAt(null);
        o.setFulfillmentNextAttemptAt(null);
        o.setFulfillmentAttempts(0);
        orderRepository.save(o);
        log.info("::> [FULFILLMENT] Reintento manual habilitado pedido={}", o.getOrderNumber());
    }

    /**
     * Sondea el tracking en el carrier, añade los eventos nuevos al timeline, actualiza el último estado y
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
        List<OrderShipmentEntity> shipments = shipmentRepository.findByOrderIdOrderBySequenceNoAsc(o.getId());
        TrackingSnapshot snap = pollShipments(o, shipments);
        // Con bultos, los eventos ya se guardaron etiquetados por guía en pollShipments; aquí solo se
        // decide a quién notificar. Sin bultos (pedidos anteriores al reparto) hay que guardarlos.
        List<TrackingStep> toNotify = collectNewSteps(o, snap, !shipments.isEmpty());
        o.setLastTrackedAt(Instant.now());
        o.setTrackingStatus(snap.currentStatus().name());
        OrderStatus current = o.getStatus();
        orderRepository.save(o);
        notifyTrackingSteps(o, toNotify);
        return new TrackingProgress(current, snap.currentStatus());
    }

    /**
     * Recorre los pasos que devolvió el carrier, guarda los que aún no estén en el timeline y devuelve
     * los que hay que notificar al comprador.
     *
     * <p>Notificamos cada cambio de estado del envío MENOS dos: el estado interno FORWARDED
     * ("registrado en el carrier"), que no le dice nada al cliente, y el PRIMER paso SHIPPED
     * ("Recogido por el transportista"), que —igual que DELIVERED— ya lo avisan shipped()/delivered()
     * en el use case al avanzar el OrderStatus. Así solo salen de aquí los pasos intermedios (en
     * tránsito, llegó al país, en reparto) y no se duplican correos.
     *
     * @param alreadyPersisted {@code true} cuando el pedido tiene bultos y {@code pollShipments} ya
     *        guardó sus eventos etiquetados por guía; entonces aquí solo se decide a quién notificar.
     */
    private List<TrackingStep> collectNewSteps(Order o, TrackingSnapshot snap, boolean alreadyPersisted) {
        List<OrderTrackingEventEntity> existing = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId());
        Set<String> seen = new HashSet<>();
        for (OrderTrackingEventEntity e : existing) {
            seen.add(e.getStatus() + "|" + e.getDescription());
        }
        boolean shippedSeen = existing.stream().anyMatch(e -> OrderStatus.SHIPPED.name().equals(e.getStatus()));
        List<TrackingStep> toNotify = new ArrayList<>();
        for (TrackingStep step : snap.steps()) {
            if (!seen.add(step.status().name() + "|" + step.description())) {
                continue;
            }
            if (!alreadyPersisted) {
                appendEvent(o.getId(), step.status().name(), step.description(), step.location(),
                        CARRIER_SOURCE, step.occurredAt());
            }
            if (step.status() == OrderStatus.SHIPPED) {
                if (shippedSeen) {
                    toNotify.add(step); // paso intermedio → notificar
                } else {
                    shippedSeen = true; // primer SHIPPED = "Recogido" → lo cubre shipped()
                }
            }
        }
        return toNotify;
    }

    /**
     * Sondea la trazabilidad de cada bulto y devuelve el estado AGREGADO del pedido.
     *
     * <p>El pedido solo está entregado cuando lo están todos sus bultos: si uno sigue en tránsito, el
     * cliente aún espera algo. Los eventos de cada guía se etiquetan con su bulto para poder enseñarlos
     * por separado.
     */
    private TrackingSnapshot pollShipments(Order o, List<OrderShipmentEntity> shipments) {
        if (shipments.isEmpty()) {
            // Pedido anterior al reparto en guías: se sondea con el número del pedido, como siempre.
            return fulfillment.track(o.getTrackingNumber(), o.getForwardedAt(), o.getShippingCountry());
        }
        List<TrackingStep> all = new ArrayList<>();
        OrderStatus aggregated = OrderStatus.DELIVERED;
        for (OrderShipmentEntity shipment : shipments) {
            String reference = shipment.getWaybillNumber() != null
                    ? shipment.getWaybillNumber() : shipment.getTrackingNumber();
            if (reference == null) {
                continue;
            }
            TrackingSnapshot own = fulfillment.track(reference, o.getForwardedAt(), o.getShippingCountry());
            shipment.setStatus(own.currentStatus().name());
            shipment.setLastTrackedAt(Instant.now());
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            appendShipmentEvents(o, shipment, own);
            all.addAll(own.steps());
            // El pedido va tan atrasado como su bulto más atrasado.
            if (own.currentStatus().ordinal() < aggregated.ordinal()) {
                aggregated = own.currentStatus();
            }
        }
        return new TrackingSnapshot(aggregated, all);
    }

    /** Guarda los eventos NUEVOS de un bulto, etiquetados con su guía para el desglose por paquete. */
    private void appendShipmentEvents(Order o, OrderShipmentEntity shipment, TrackingSnapshot snap) {
        List<OrderTrackingEventEntity> own = trackingRepository.findByShipmentIdOrderByOccurredAtAsc(shipment.getId());
        Set<String> seen = new HashSet<>();
        for (OrderTrackingEventEntity e : own) {
            seen.add(e.getStatus() + "|" + e.getDescription());
        }
        for (TrackingStep step : snap.steps()) {
            if (seen.add(step.status().name() + "|" + step.description())) {
                trackingRepository.save(OrderTrackingEventEntity.builder()
                        .orderId(o.getId()).shipmentId(shipment.getId()).status(step.status().name())
                        .description(step.description()).location(step.location()).source(CARRIER_SOURCE)
                        .occurredAt(step.occurredAt() != null ? step.occurredAt() : Instant.now())
                        .createdAt(Instant.now()).build());
            }
        }
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

    /**
     * Un bulto del pedido con su propia trazabilidad: "Paquete i de N", su guía y sus eventos.
     *
     * <p>Los bultos de un mismo pedido avanzan a ritmos distintos —uno puede estar en aduana y otro ya
     * en reparto—, así que el seguimiento se enseña por paquete y no todo mezclado en una sola lista.
     */
    public record ShipmentTrackingView(int sequenceNo, String carrier, String trackingNumber, String status,
            int weightGrams, Instant estimatedDeliveryAt, List<TrackingEventView> events) {
    }

    /**
     * Seguimiento del pedido. {@code events} mantiene la lista completa —lo que ya consumía la interfaz—
     * y {@code shipments} añade el desglose por bulto para los pedidos repartidos en varias guías.
     */
    public record TrackingView(String status, String carrier, String trackingNumber, Instant estimatedDeliveryAt,
            Instant lastTrackedAt, List<TrackingEventView> events, List<ShipmentTrackingView> shipments) {
    }

    /** Vista de tracking del pedido del usuario (valida propiedad). */
    @Transactional(readOnly = true)
    public TrackingView myTrackingView(UUID userId, UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException(ORDER));
        if (o.getUserId() == null || !o.getUserId().equals(userId)) {
            throw new NotFoundException(ORDER);
        }
        return view(o);
    }

    /** Vista de tracking para el admin. */
    @Transactional(readOnly = true)
    public TrackingView adminTrackingView(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException(ORDER));
        return view(o);
    }

    private TrackingView view(Order o) {
        List<OrderTrackingEventEntity> all = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId());
        List<TrackingEventView> events = trackingViewMapper.toEventViews(all);
        return new TrackingView(o.getStatus() != null ? o.getStatus().name() : null, o.getCarrier(),
                o.getTrackingNumber(), o.getEstimatedDeliveryAt(), o.getLastTrackedAt(), events,
                shipmentViews(o, all));
    }

    /**
     * Desglose por bulto. Se devuelve vacío cuando el pedido viaja en un solo paquete: en ese caso la
     * lista global ya lo dice todo y añadir un "Paquete 1 de 1" solo sería ruido.
     */
    private List<ShipmentTrackingView> shipmentViews(Order o, List<OrderTrackingEventEntity> allEvents) {
        List<OrderShipmentEntity> shipments = shipmentRepository.findByOrderIdOrderBySequenceNoAsc(o.getId());
        if (shipments.size() <= 1) {
            return List.of();
        }
        List<ShipmentTrackingView> views = new ArrayList<>();
        for (OrderShipmentEntity shipment : shipments) {
            List<TrackingEventView> own = trackingViewMapper.toEventViews(allEvents.stream()
                    .filter(e -> shipment.getId().equals(e.getShipmentId())).toList());
            views.add(trackingViewMapper.toShipmentView(shipment, own));
        }
        return views;
    }

    /** Registra un evento del timeline a mano (p.ej. desde el admin). */
    // Sin @Transactional propia: sólo se llama desde métodos de esta clase que ya la abren, y por
    // autoinvocación la anotación no llegaba a aplicarse. Participa en la transacción del llamador.
    public void appendEvent(UUID orderId, String status, String description, String location, String source,
            Instant occurredAt) {
        trackingRepository.save(OrderTrackingEventEntity.builder().orderId(orderId).status(status)
                .description(description).location(location).source(source)
                .occurredAt(occurredAt != null ? occurredAt : Instant.now()).createdAt(Instant.now()).build());
    }

    // ─────────────────────── Push entrante de YunExpress (事件管理) ───────────────────────

    /**
     * Aplica un push de YunExpress al timeline del pedido. El controller ya verificó la firma; aquí se
     * descifra el contenido (si viene cifrado), se resuelve el pedido por guía / número de cliente y se
     * añaden los eventos NUEVOS con el mismo dedup que el sondeo.
     *
     * <p>El sobre del push trae el contenido en {@code encrypt} (AES) o directamente en claro según la
     * política de cifrado de la aplicación; se admiten ambos para no depender de esa configuración.
     */
    @Transactional
    public void applyYunExpressPush(String rawBody) {
        JsonNode payload = parseYunExpressPayload(rawBody);
        if (payload == null) {
            return;
        }
        Order o = resolveYunExpressOrder(payload);
        if (o == null) {
            log.warn("YunExpress push: pedido no encontrado para el evento recibido");
            return;
        }
        JsonNode nested = payload.path("track_Info").path("track_events");
        JsonNode events = nested.isArray() && !nested.isEmpty() ? nested : payload.path("track_events");
        YunExpressFulfillmentService provider = yunExpressProvider().orElse(null);
        if (provider == null) {
            log.warn("YunExpress push: el proveedor activo no es YunExpress — evento ignorado");
            return;
        }
        TrackingSnapshot snap = provider.toSnapshot(events, o.getShippingCountry());
        boolean changed = false;
        for (TrackingStep step : snap.steps()) {
            changed |= appendIfNew(o, step.status(), step.description(), step.location(), step.occurredAt(),
                    CARRIER_SOURCE);
        }
        if (changed) {
            o.setLastTrackedAt(Instant.now());
            orderRepository.save(o);
            log.info("YunExpress push: timeline actualizado para pedido {}", o.getOrderNumber());
        }
    }

    /** Desenvuelve el push: descifra {@code encrypt} si viene cifrado, o usa el cuerpo tal cual. */
    private JsonNode parseYunExpressPayload(String rawBody) {
        try {
            JsonNode envelope = objectMapper.readTree(rawBody);
            String encrypted = envelope.path("encrypt").asText(null);
            if (encrypted == null || encrypted.isBlank()) {
                return envelope;
            }
            return objectMapper.readTree(eventCipher.decrypt(encrypted));
        } catch (JsonProcessingException e) {
            log.warn("YunExpress push: JSON inválido");
            return null;
        }
    }

    /** Resuelve el pedido del push por guía, tracking o nuestro número de pedido. */
    private Order resolveYunExpressOrder(JsonNode payload) {
        JsonNode info = payload.path("track_Info");
        for (String key : new String[] { "waybill_number", "order_number", "shipment_number", "tracking_number" }) {
            String value = firstNodeText(payload, key);
            if (value == null) {
                value = firstNodeText(info, key);
            }
            if (value != null) {
                Order found = orderRepository.findByTrackingNumber(value).orElse(null);
                if (found != null) {
                    return found;
                }
            }
        }
        String customerOrder = firstNodeText(payload, "customer_order_number");
        if (customerOrder == null) {
            customerOrder = firstNodeText(info, "customer_order_number");
        }
        return customerOrder != null ? orderRepository.findByOrderNumber(customerOrder).orElse(null) : null;
    }

    /**
     * Añade el evento si no estaba ya (dedup por estado|descripción) y deja constancia de quién lo trajo.
     * Devuelve true si lo añadió, para saber si hay que tocar el pedido.
     */
    private boolean appendIfNew(Order o, OrderStatus status, String desc, String location, Instant when,
            String source) {
        if (desc == null || desc.isBlank()) {
            return false;
        }
        String key = status.name() + "|" + desc;
        boolean exists = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId()).stream()
                .anyMatch(e -> key.equals(e.getStatus() + "|" + e.getDescription()));
        if (exists) {
            return false;
        }
        appendEvent(o.getId(), status.name(), desc, location, source, when);
        o.setTrackingStatus(status.name());
        return true;
    }

    /** El proveedor activo, cuando es YunExpress (única implementación cableada hoy). */
    private Optional<YunExpressFulfillmentService> yunExpressProvider() {
        return fulfillment instanceof YunExpressFulfillmentService yun ? Optional.of(yun) : Optional.empty();
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

}
