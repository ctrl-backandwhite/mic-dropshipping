package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
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
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
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
    /**
     * A quién se le pide la guía y el seguimiento de cada pedido.
     *
     * <p>Antes aquí había un {@code FulfillmentProvider} suelto, y con varios transportistas eso
     * significaba usar siempre el primario: un pedido cobrado por uno se despachaba por otro y se seguía
     * preguntándole al equivocado por una guía que no era suya.
     */
    private final FulfillmentProviderSelector transportistas;
    private final UserRepository userRepository;
    private final NotificationsPublisher notificationsPublisher;
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
    private final OrderShipmentItemRepository shipmentItemRepository;
    /** Proyección de entidades de seguimiento a las vistas de la API (MapStruct). */
    private final TrackingViewMapper trackingViewMapper;
    /** Compras al proveedor: sin mercancía comprada y en camino no se emite guía internacional. */
    private final SupplierPurchaseService supplierPurchaseService;

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
        // La guía se paga y arranca el reloj del seguimiento del cliente. Hasta aquí bastaba con que
        // alguien pulsara «despachar» para emitirla, y con bulk-forward se podían emitir decenas de
        // golpe: guías reales para mercancía que todavía no se había comprado en 1688. Ahora se exige
        // que TODOS los bultos del pedido vayan camino del almacén chino.
        if (!supplierPurchaseService.readyForInternationalShipment(orderId)) {
            log.debug("Fulfillment: pedido {} aún sin mercancía en camino; no se crea la guía",
                    o.getOrderNumber());
            return;
        }
        if (!readyForAttempt(o)) {
            return; // rendido, o aún dentro de la espera del backoff
        }
        // La guía se le pide a quien cobró el porte. Si ese transportista no está —apagado, o un valor
        // que ya no existe—, NO se despacha por el otro: sería pagar un porte distinto del cobrado y
        // enviar por quien el cliente no eligió. Se deja en la bandeja para que alguien lo mire.
        FulfillmentProvider transportista = transportistas.para(o).orElse(null);
        if (transportista == null) {
            recordFailure(o, new FulfillmentFailure(FulfillmentFailure.Kind.PERMANENT,
                    "El pedido " + o.getOrderNumber() + " se cobró por el transportista "
                            + o.getShippingCarrier() + ", que ahora mismo no está disponible. No se despacha"
                            + " por otro: habría que cobrar de nuevo. Actívalo o corrige el pedido."));
            return;
        }
        // Lo que solo el transportista sabe: no puede emitir la guía mientras no tenga la mercancía
        // dada de alta en su inventario. Es «todavía no», no un fallo, así que se espera al siguiente
        // intento sin ensuciar la bandeja de incidencias. Va detrás del backoff a propósito: la API
        // admite una petición por segundo y así el ritmo lo marca la espera que ya existe.
        if (!transportista.readyToShip(o)) {
            log.debug("Fulfillment: {} aún no puede emitir la guía del pedido {}; se reintentará",
                    transportista.nombre(), o.getOrderNumber());
            return;
        }
        List<FulfillmentResult> results;
        try {
            results = transportista.createShipments(o);
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
        // El origen es el almacén donde se reempaqueta, no la sede del transportista: Yunfulfillment
        // opera en Dongguan (CNCHASHAN). Decía Shenzhen desde antes de que existiera el almacén, cuando
        // el punto de salida era una suposición.
        appendEvent(o.getId(), OrderStatus.FORWARDED.name(), "Envío registrado para entrega", "Dongguan, CN", "SYSTEM",
                o.getForwardedAt() != null ? o.getForwardedAt() : Instant.now());
        log.info("Fulfillment: envío {} creado para pedido {}", r.trackingNumber(), o.getOrderNumber());
    }

    /**
     * Guarda qué líneas del pedido —y cuántas unidades— van dentro del bulto.
     *
     * <p>El transportista devuelve el reparto por índice de línea, que es como lo calculó el repartidor;
     * aquí se traduce al identificador real de la línea para que el seguimiento pueda pintar su foto.
     *
     * <p>Un índice fuera de rango se descarta en lugar de reventar: dejar el bulto sin detalle es un
     * seguimiento menos rico, pero perder la guía por eso sería perder el envío entero.
     */
    private void persistShipmentContents(Order o, OrderShipmentEntity shipment, FulfillmentResult r) {
        List<OrderItem> items = o.getItems();
        if (items == null || r.contents() == null) {
            return;
        }
        for (FulfillmentProvider.ParcelContent content : r.contents()) {
            if (content.lineIndex() < 0 || content.lineIndex() >= items.size()) {
                log.warn("Fulfillment: el bulto {} referencia una línea inexistente ({}) del pedido {}",
                        shipment.getSequenceNo(), content.lineIndex(), o.getOrderNumber());
                continue;
            }
            shipmentItemRepository.save(OrderShipmentItemEntity.builder()
                    .shipmentId(shipment.getId())
                    .orderItemId(items.get(content.lineIndex()).getId())
                    .quantity(content.quantity())
                    .build());
        }
    }

    /** Da de alta en {@code order_shipment} cada bulto creado en el transportista, con su contenido. */
    private void persistShipments(Order o, List<FulfillmentResult> results) {
        for (FulfillmentResult r : results) {
            OrderShipmentEntity shipment = shipmentRepository.save(OrderShipmentEntity.builder()
                    .orderId(o.getId()).sequenceNo(r.sequenceNo()).carrier(r.carrier())
                    .productCode(r.productCode()).waybillNumber(r.fulfillmentRef())
                    .trackingNumber(r.trackingNumber()).status(OrderStatus.FORWARDED.name())
                    .weightGrams(r.weightGrams()).declaredValueCents(r.declaredValueCents())
                    .declaration(declarationJson(r))
                    .estimatedDeliveryAt(Instant.now().plus(Duration.ofDays(r.etaMaxDays())))
                    .createdAt(Instant.now()).build());
            persistShipmentContents(o, shipment, r);
        }
        if (results.size() > 1) {
            log.info("::> [FULFILLMENT] Pedido {} despachado en {} bultos", o.getOrderNumber(), results.size());
        }
    }

    /**
     * La declaración transmitida al transportista, lista para archivarse como jsonb.
     *
     * <p>Es lo único que deja constancia de QUÉ se declaró: destinatario y líneas con su partida
     * arancelaria. Si aduana rechaza el envío, sin esto habría que ir al panel del transportista.
     *
     * <p>Un fallo al convertirla NO tumba el despacho: la guía ya existe en el transportista y perder el
     * envío por no poder archivar una copia sería mucho peor que quedarse sin la copia.
     */
    private Map<String, Object> declarationJson(FulfillmentResult r) {
        if (r.declaration() == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(r.declaration(), new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException e) {
            log.warn("Fulfillment: no se pudo archivar la declaración del bulto {}: {}", r.sequenceNo(),
                    e.getMessage());
            return null;
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
        // El timeline TAL COMO ESTABA ANTES de sondear. Se lee aquí y no dentro de collectNewSteps porque
        // pollShipments ya guarda los eventos nuevos de cada guía: releerlo después devolvía también los
        // que acababan de llegar, todos los pasos salían por «ya conocidos» y no se avisaba de NINGUNO. Con
        // el reparto en bultos —que hoy usan todos los pedidos— eso dejó al comprador sin un solo correo de
        // «en tránsito»: llegada al país, en aduana o en reparto se guardaban en silencio. Es el mismo
        // fallo que ya se corrigió en el push entrante y que aquí seguía vivo.
        List<OrderTrackingEventEntity> anteriores = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId());
        TrackingSnapshot snap = pollShipments(o, shipments);
        // Con bultos, los eventos ya se guardaron etiquetados por guía en pollShipments; aquí solo se
        // decide a quién notificar. Sin bultos (pedidos anteriores al reparto) hay que guardarlos.
        List<TrackingStep> toNotify = collectNewSteps(o, snap, !shipments.isEmpty(), anteriores);
        o.setLastTrackedAt(Instant.now());
        advanceTrackingStatus(o, snap.currentStatus());
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
     * @param existing el timeline tal como estaba ANTES de sondear al transportista. Lo pasa el llamante
     *        justamente porque leerlo aquí llegaría tarde: ver la nota de {@link #pollEvents}.
     */
    private List<TrackingStep> collectNewSteps(Order o, TrackingSnapshot snap, boolean alreadyPersisted,
            List<OrderTrackingEventEntity> existing) {
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
    /**
     * Avisa de la entrega de UN bulto cuando el pedido viaja en varios.
     *
     * <p>El aviso de «entregado» es del pedido entero y solo salta cuando ha llegado el último. Con dos
     * paquetes que llegan con días de diferencia, quien compró recibe uno y no le llega nada: cree que
     * falta y escribe. Este aviso es por bulto y solo existe si hay más de uno; con un único paquete
     * sería el mismo mensaje dos veces.
     *
     * <p>Un fallo al avisar no interrumpe el sondeo del resto de bultos.
     */
    private void avisarBultoEntregado(Order o, OrderShipmentEntity shipment, String anterior,
            OrderStatus ahora, int totalBultos) {
        if (totalBultos <= 1 || ahora != OrderStatus.DELIVERED || o.getUserId() == null
                || OrderStatus.DELIVERED.name().equals(anterior)) {
            return;
        }
        try {
            User u = userRepository.getById(o.getUserId());
            if (u == null) {
                return;
            }
            notificationsPublisher.dispatch("ORDER_PARCEL_DELIVERED", o.getUserId(), u.getEmail(),
                    Map.of("orderNumber", o.getOrderNumber(),
                            "parcel", shipment.getSequenceNo(),
                            "parcels", totalBultos,
                            "trackingNumber", shipment.getTrackingNumber() != null
                                    ? shipment.getTrackingNumber() : ""),
                    u.getLanguage());
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar de la entrega del bulto {} del pedido {}: {}",
                    shipment.getSequenceNo(), o.getOrderNumber(), e.getMessage());
        }
    }

    private TrackingSnapshot pollShipments(Order o, List<OrderShipmentEntity> shipments) {
        // Al transportista del pedido, no al primario: preguntarle a YunExpress por una guía de otro no da
        // error, devuelve «no sé nada» —y el cliente se queda mirando un seguimiento que nunca avanza—.
        FulfillmentProvider transportista = transportistas.para(o).orElse(null);
        if (transportista == null) {
            // Sin transportista no se inventa nada: un hito falso en el timeline es peor que ninguno.
            log.warn("Seguimiento: el pedido {} se cobró por {}, que no está disponible; no se sondea",
                    o.getOrderNumber(), o.getShippingCarrier());
            return new TrackingSnapshot(o.getStatus(), List.of());
        }
        if (shipments.isEmpty()) {
            // Pedido anterior al reparto en guías: se sondea con el número del pedido, como siempre.
            return transportista.track(o.getTrackingNumber(), o.getForwardedAt(), o.getShippingCountry());
        }
        List<TrackingStep> all = new ArrayList<>();
        OrderStatus aggregated = OrderStatus.DELIVERED;
        for (OrderShipmentEntity shipment : shipments) {
            String reference = shipment.getWaybillNumber() != null
                    ? shipment.getWaybillNumber() : shipment.getTrackingNumber();
            if (reference == null) {
                continue;
            }
            TrackingSnapshot own = transportista.track(reference, o.getForwardedAt(), o.getShippingCountry());
            String anterior = shipment.getStatus();
            shipment.setStatus(own.currentStatus().name());
            shipment.setLastTrackedAt(Instant.now());
            shipment.setUpdatedAt(Instant.now());
            shipmentRepository.save(shipment);
            appendShipmentEvents(o, shipment, own);
            avisarBultoEntregado(o, shipment, anterior, own.currentStatus(), shipments.size());
            all.addAll(own.steps());
            // El pedido va tan atrasado como su bulto más atrasado. Se compara por progress() y no por
            // ordinal(): CANCELLED y REFUNDED están declarados DETRÁS de DELIVERED, así que por posición
            // un bulto devuelto contaba como el más avanzado y dejaba el pedido entero como entregado.
            if (own.currentStatus().progress() < aggregated.progress()) {
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
            int weightGrams, Instant estimatedDeliveryAt, List<TrackingEventView> events,
            List<ParcelItemView> items) {
    }

    /**
     * Un artículo dentro de un bulto: lo justo para reconocerlo de un vistazo.
     *
     * <p>Sin esto el seguimiento decía «Paquete 1/2» y nada más, y quien recibía uno no sabía a qué le
     * estaba siguiendo la pista.
     */
    public record ParcelItemView(String title, String imageUrl, String variantName, int quantity) {
    }

    /**
     * Lo que se le declaró al transportista para UN bulto, con la guía a la que corresponde.
     *
     * <p>Solo se devuelve en la vista del admin: lleva partidas arancelarias, valores declarados y la
     * referencia del proveedor, que no son datos del comprador.
     */
    public record ShipmentDeclarationView(int sequenceNo, String trackingNumber, String waybillNumber,
            FulfillmentProvider.ShipmentDeclaration declaration) {
    }

    /**
     * Seguimiento del pedido. {@code events} mantiene la lista completa —lo que ya consumía la interfaz—
     * y {@code shipments} añade el desglose por bulto para los pedidos repartidos en varias guías.
     *
     * <p>{@code declarations} es lo que se transmitió al transportista por cada bulto y va SIEMPRE vacío
     * fuera del panel de administración.
     */
    public record TrackingView(String status, String carrier, String trackingNumber, Instant estimatedDeliveryAt,
            Instant lastTrackedAt, List<TrackingEventView> events, List<ShipmentTrackingView> shipments,
            List<ShipmentDeclarationView> declarations) {
    }

    /** Vista de tracking del pedido del usuario (valida propiedad). */
    @Transactional(readOnly = true)
    public TrackingView myTrackingView(UUID userId, UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException(ORDER));
        if (o.getUserId() == null || !o.getUserId().equals(userId)) {
            throw new NotFoundException(ORDER);
        }
        return view(o, false);
    }

    /** Vista de tracking para el admin: la única que incluye la declaración enviada al transportista. */
    @Transactional(readOnly = true)
    public TrackingView adminTrackingView(UUID orderId) {
        Order o = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException(ORDER));
        return view(o, true);
    }

    private TrackingView view(Order o, boolean forAdmin) {
        List<OrderTrackingEventEntity> all = trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId());
        List<TrackingEventView> events = trackingViewMapper.toEventViews(all);
        // Los bultos se leen una sola vez y se reparten entre el desglose y las declaraciones: son la
        // misma pantalla, y pedirlos dos veces sería una consulta de más por cada visita a la ficha.
        List<OrderShipmentEntity> shipments = shipmentRepository.findByOrderIdOrderBySequenceNoAsc(o.getId());
        return new TrackingView(o.getStatus() != null ? o.getStatus().name() : null, o.getCarrier(),
                o.getTrackingNumber(), o.getEstimatedDeliveryAt(), o.getLastTrackedAt(), events,
                shipmentViews(o, all, shipments), forAdmin ? declarationViews(shipments) : List.of());
    }

    /**
     * Lo declarado al transportista, bulto a bulto, para que el admin pueda comprobar desde la ficha del
     * pedido que la guía salió completa y a la dirección correcta.
     *
     * <p>Los envíos anteriores a que esto se archivara no aportan entrada: la ficha no pinta el bloque y
     * ya está, igual que hace el contenido del bulto.
     */
    private List<ShipmentDeclarationView> declarationViews(List<OrderShipmentEntity> shipments) {
        List<ShipmentDeclarationView> out = new ArrayList<>();
        for (OrderShipmentEntity shipment : shipments) {
            FulfillmentProvider.ShipmentDeclaration declared = readDeclaration(shipment);
            if (declared != null) {
                out.add(new ShipmentDeclarationView(shipment.getSequenceNo(), shipment.getTrackingNumber(),
                        shipment.getWaybillNumber(), declared));
            }
        }
        return out;
    }

    /**
     * Relee la declaración archivada. Un json que ya no encaje con el modelo se ignora en lugar de tumbar
     * la ficha del pedido: el admin necesita poder abrirla sobre todo cuando algo ha ido mal.
     */
    private FulfillmentProvider.ShipmentDeclaration readDeclaration(OrderShipmentEntity shipment) {
        Map<String, Object> stored = shipment.getDeclaration();
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(stored, FulfillmentProvider.ShipmentDeclaration.class);
        } catch (IllegalArgumentException e) {
            log.warn("Fulfillment: declaración archivada ilegible en el bulto {}: {}",
                    shipment.getSequenceNo(), e.getMessage());
            return null;
        }
    }

    /**
     * Desglose por bulto. Se devuelve vacío cuando el pedido viaja en un solo paquete: en ese caso la
     * lista global ya lo dice todo y añadir un "Paquete 1 de 1" solo sería ruido.
     */
    private List<ShipmentTrackingView> shipmentViews(Order o, List<OrderTrackingEventEntity> allEvents,
            List<OrderShipmentEntity> shipments) {
        if (shipments.size() <= 1) {
            return List.of();
        }
        // Contenido de todos los bultos de una vez: pedirlo dentro del bucle sería una consulta por
        // paquete para pintar una sola pantalla.
        Map<UUID, List<OrderShipmentItemEntity>> contenidos = shipmentItemRepository
                .findByShipmentIdIn(shipments.stream().map(OrderShipmentEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(OrderShipmentItemEntity::getShipmentId));
        Map<UUID, OrderItem> lineas = new HashMap<>();
        if (o.getItems() != null) {
            o.getItems().forEach(i -> lineas.put(i.getId(), i));
        }
        List<ShipmentTrackingView> views = new ArrayList<>();
        for (OrderShipmentEntity shipment : shipments) {
            List<TrackingEventView> own = trackingViewMapper.toEventViews(allEvents.stream()
                    .filter(e -> shipment.getId().equals(e.getShipmentId())).toList());
            views.add(trackingViewMapper.toShipmentView(shipment, own,
                    parcelItems(contenidos.getOrDefault(shipment.getId(), List.of()), lineas)));
        }
        return views;
    }

    /**
     * Traduce el contenido guardado del bulto a lo que se pinta.
     *
     * <p>Los envíos creados antes de que esto existiera no tienen contenido registrado: devuelven lista
     * vacía y el seguimiento se pinta como siempre, sin fotos, en vez de fallar.
     */
    private List<ParcelItemView> parcelItems(List<OrderShipmentItemEntity> contenido,
            Map<UUID, OrderItem> lineas) {
        List<ParcelItemView> out = new ArrayList<>();
        for (OrderShipmentItemEntity item : contenido) {
            OrderItem linea = lineas.get(item.getOrderItemId());
            if (linea == null) {
                continue;
            }
            out.add(new ParcelItemView(linea.getTitleSnapshot(), linea.getImageUrlSnapshot(),
                    linea.getVariantName(), item.getQuantity()));
        }
        return out;
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
        // El timeline se lee UNA vez para todo el push. Antes se releía entero por cada paso —N consultas
        // por notificación— y, peor, los pasos añadidos en este mismo push no se veían entre sí salvo que
        // la sesión hiciera flush, así que un push con el mismo evento repetido lo insertaba dos veces.
        Set<String> known = new HashSet<>();
        for (OrderTrackingEventEntity e : trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId())) {
            known.add(e.getStatus() + "|" + e.getDescription());
        }
        boolean changed = false;
        // Mismo criterio de aviso que el sondeo: los pasos intermedios en tránsito se notifican, pero ni
        // FORWARDED (interno) ni el PRIMER SHIPPED ("recogido") ni la entrega, que tienen correo propio.
        // Sin esto, con el push activo el comprador no recibía NINGÚN correo de "en tránsito": el push
        // guardaba los pasos sin avisar y el sondeo, al verlos ya guardados, tampoco avisaba.
        boolean shippedSeen = known.stream().anyMatch(k -> k.startsWith(OrderStatus.SHIPPED.name() + "|"));
        List<TrackingStep> toNotify = new ArrayList<>();
        for (TrackingStep step : snap.steps()) {
            boolean added = appendIfNew(o, known, step.status(), step.description(), step.location(),
                    step.occurredAt(), CARRIER_SOURCE);
            changed |= added;
            if (added && step.status() == OrderStatus.SHIPPED) {
                if (shippedSeen) {
                    toNotify.add(step);
                } else {
                    shippedSeen = true;
                }
            }
        }
        if (changed) {
            o.setLastTrackedAt(Instant.now());
            orderRepository.save(o);
            notifyTrackingSteps(o, toNotify);
            log.info("YunExpress push: timeline actualizado para pedido {}", o.getOrderNumber());
        } else if (!snap.steps().isEmpty()) {
            // Todos los pasos venían repetidos: normal, el transportista reenvía.
            log.debug("YunExpress push: pedido {} sin novedades ({} pasos ya conocidos)",
                    o.getOrderNumber(), snap.steps().size());
        } else if (events.isArray() && !events.isEmpty()) {
            // Esto NO es normal: el push trae eventos y no hemos sacado ni un paso de ellos. Pasa cuando
            // el transportista cambia el nombre de un campo o manda códigos que no reconocemos. Sin este
            // aviso el push se acepta con un 200, no se guarda nada y nadie se entera de que el
            // seguimiento se quedó congelado.
            log.warn("YunExpress push: pedido {} traía {} eventos y ninguno se ha podido interpretar"
                    + " — ¿ha cambiado el formato del proveedor?", o.getOrderNumber(), events.size());
        } else {
            log.warn("YunExpress push: pedido {} recibido sin eventos", o.getOrderNumber());
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
    private boolean appendIfNew(Order o, Set<String> known, OrderStatus status, String desc, String location,
            Instant when, String source) {
        if (desc == null || desc.isBlank()) {
            return false;
        }
        // `known` se actualiza aquí mismo: así el dedup vale también entre los pasos de un mismo push,
        // sin depender de cuándo decida la sesión de JPA volcar los INSERT a la base de datos.
        if (!known.add(status.name() + "|" + desc)) {
            return false;
        }
        appendEvent(o.getId(), status.name(), desc, location, source, when);
        advanceTrackingStatus(o, status);
        return true;
    }

    /**
     * Mueve el estado de seguimiento SOLO hacia adelante.
     *
     * <p>Sin esto el estado retrocedía por dos caminos distintos, y los dos se han visto de verdad. El
     * sondeo periódico escribía lo que dijera el carrier en ese momento, así que un pedido ya marcado
     * ENTREGADO por un push volvía a "en tránsito" en cuanto el proveedor tardaba en consolidar el
     * último evento en su API. Y en un push con varios eventos el estado quedaba en el ÚLTIMO del
     * array, que no tiene por qué ser el más avanzado: basta con que YunExpress los mande desordenados
     * —cosa que hace, porque los agrupa por nodo y no por hora— para que un pedido entregado se quede
     * anunciando la salida del almacén.
     *
     * <p>Ver a un pedido desandar el camino es de las cosas que más desconfianza generan en el
     * comprador, y además dispara avisos que ya se habían mandado.
     *
     * <p>Los estados fuera de la escala de avance ({@code progress() < 0}: cancelado, reembolsado) sí se
     * aplican siempre. No son un paso atrás sino un desenlace distinto, y perder esa información sería
     * peor que perder el orden.
     */
    private void advanceTrackingStatus(Order o, OrderStatus candidate) {
        if (candidate == null) {
            return;
        }
        OrderStatus actual = parseTrackingStatus(o.getTrackingStatus());
        if (actual == null || candidate.progress() < 0 || candidate.progress() > actual.progress()) {
            o.setTrackingStatus(candidate.name());
        }
    }

    /** El estado guardado, o null si está vacío o es un valor que ya no existe en el enum. */
    private static OrderStatus parseTrackingStatus(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * YunExpress, cuando hace falta su implementación concreta.
     *
     * <p>Lo piden sus propios mensajes —el webhook llega con su formato y su cifrado—, que no traen
     * pedido con el que decidir. Se le pregunta al selector por su nombre en vez de mirar «el» proveedor
     * inyectado: desde que hay dos, ese ya no es necesariamente él.
     */
    private Optional<YunExpressFulfillmentService> yunExpressProvider() {
        return transportistas.deTipo(YunExpressFulfillmentService.class);
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
