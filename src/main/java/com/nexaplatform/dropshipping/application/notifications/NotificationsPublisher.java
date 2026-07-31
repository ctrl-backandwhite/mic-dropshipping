package com.nexaplatform.dropshipping.application.notifications;

import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica eventos a Kafka que serán consumidos por el microservicio
 * {@code mic-notificationservice} (proyecto ecommerce). Este servicio es
 * AGNÓSTICO al canal final (email/push/SMS) — sólo enuncia el "qué pasó" y
 * deja que el otro servicio decida plantilla, idioma y delivery.
 *
 * <p><b>Contrato del payload:</b> cada método produce un mapa JSON con los
 * campos mínimos para que el consumer pueda renderizar la notificación sin
 * tener que volver a llamar al backend de dropshipping. Esto es importante
 * para mantener los dos microservicios desacoplados.
 *
 * <p>Todas las llamadas requieren una transacción abierta — el evento se
 * persiste en {@code event_outbox} y se publica cuando la tx hace commit
 * (garantía at-least-once).
 */
@Service
@RequiredArgsConstructor
public class NotificationsPublisher {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String ORDERNUMBER = "orderNumber";
    private static final String ORDER = "Order";

    private final EventPublisher events;

    /* ============== Órdenes ============== */

    @Transactional(propagation = Propagation.MANDATORY)
    public void orderPlaced(UUID userId, String userEmail, String orderNumber, String displayTotal, String currency,
            String locale) {
        Map<String, Object> body = base("ORDER_PLACED", userId, userEmail, locale);
        body.put(ORDERNUMBER, orderNumber);
        body.put("totalDisplay", displayTotal);
        body.put("currency", currency);
        events.publish(NexaTopics.NOTIFICATIONS_ORDER_PLACED, ORDER, orderNumber, keyOf(userId, userEmail), body);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void orderShipped(UUID userId, String userEmail, String orderNumber, String carrier, String trackingNumber,
            String locale) {
        Map<String, Object> body = base("ORDER_SHIPPED", userId, userEmail, locale);
        body.put(ORDERNUMBER, orderNumber);
        body.put("carrier", carrier);
        body.put("trackingNumber", trackingNumber);
        events.publish(NexaTopics.NOTIFICATIONS_ORDER_SHIPPED, ORDER, orderNumber, keyOf(userId, userEmail), body);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void orderDelivered(UUID userId, String userEmail, String orderNumber, String locale) {
        Map<String, Object> body = base("ORDER_DELIVERED", userId, userEmail, locale);
        body.put(ORDERNUMBER, orderNumber);
        events.publish(NexaTopics.NOTIFICATIONS_ORDER_DELIVERED, ORDER, orderNumber, keyOf(userId, userEmail), body);
    }

    /* ============== Wallet ============== */

    @Transactional(propagation = Propagation.MANDATORY)
    public void walletRecharged(UUID userId, String userEmail, long amountUsdCents, String method, String locale) {
        Map<String, Object> body = base("WALLET_RECHARGED", userId, userEmail, locale);
        body.put("amountUsdCents", amountUsdCents);
        body.put("method", method);
        events.publish(NexaTopics.NOTIFICATIONS_WALLET_RECHARGED, "Wallet", keyOf(userId, userEmail),
                keyOf(userId, userEmail), body);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void walletCharged(UUID userId, String userEmail, long amountUsdCents, String orderNumber, String locale) {
        Map<String, Object> body = base("WALLET_CHARGED", userId, userEmail, locale);
        body.put("amountUsdCents", amountUsdCents);
        body.put(ORDERNUMBER, orderNumber);
        events.publish(NexaTopics.NOTIFICATIONS_WALLET_CHARGED, "Wallet", keyOf(userId, userEmail),
                keyOf(userId, userEmail), body);
    }

    /* ============== Auth ============== */

    @Transactional(propagation = Propagation.MANDATORY)
    public void authEvent(UUID userId, String userEmail, String kind, Map<String, Object> extra, String locale) {
        Map<String, Object> body = base(kind, userId, userEmail, locale);
        if (extra != null)
            body.putAll(extra);
        events.publish(NexaTopics.NOTIFICATIONS_AUTH, "User", userId != null ? userId.toString() : userEmail,
                userId != null ? userId.toString() : userEmail, body);
    }

    /* ============== Genérico (canal libre) ============== */

    /**
     * Notificación genérica — el microservicio de notificaciones se encarga
     * del canal/plantilla. Útil para "hooks" futuros sin tener que añadir
     * nuevos topics.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void dispatch(String kind, UUID userId, String userEmail, Map<String, Object> extra, String locale) {
        Map<String, Object> body = base(kind, userId, userEmail, locale);
        if (extra != null)
            body.putAll(extra);
        events.publish(NexaTopics.NOTIFICATIONS_DISPATCH, "User", userId != null ? userId.toString() : userEmail,
                userId != null ? userId.toString() : userEmail, body);
    }

    private Map<String, Object> base(String kind, UUID userId, String userEmail, String locale) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("userId", userId != null ? userId.toString() : null);
        body.put("userEmail", userEmail);
        body.put("locale", locale != null ? locale : "es");
        body.put("emittedAt", Instant.now().toString());
        body.put("source", "nx036-dropshipping");
        return body;
    }

    /**
     * Clave de partición del evento. El identificador de usuario puede faltar (compra de invitado,
     * operación disparada por el sistema); en ese caso se usa el correo, como ya hacían las
     * notificaciones de autenticación. Antes se llamaba a toString() sin más y reventaba con NPE.
     */
    private static String keyOf(UUID userId, String userEmail) {
        return userId != null ? userId.toString() : userEmail;
    }
}
