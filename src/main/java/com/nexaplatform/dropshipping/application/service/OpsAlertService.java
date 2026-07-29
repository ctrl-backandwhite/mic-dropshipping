package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CarrierErrorMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Avisos por correo al responsable de la plataforma cuando un servicio del que depende el negocio deja
 * de funcionar: el transportista no acepta envíos, o una pasarela de pago rechaza cobrar.
 *
 * <p>Son fallos que el cliente no puede resolver y que, sin aviso, solo se descubren mirando logs o
 * cuando alguien reclama. El correo llega a {@code nexadrop.alerts.email}.
 *
 * <p><b>Antiavalancha.</b> Si el transportista se cae, cada pedido pendiente generaría su propio aviso y
 * el buzón quedaría inservible justo cuando hay que leerlo. Por eso los avisos se agrupan por
 * {@link Alert#dedupeKey()} y solo se envía uno por clave cada {@code cooldown-minutes}; los suprimidos
 * quedan en el log. La ventana se guarda en memoria: un reinicio permite un aviso de más, que es
 * preferible a montar persistencia para esto.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsAlertService {

    /** Qué está fallando. Define el asunto y agrupa los avisos. */
    public enum AlertKind {
        FULFILLMENT("Envío"),
        PAYMENT("Pago");

        private final String label;

        AlertKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Un aviso. {@code dedupeKey} es lo que decide qué se considera "el mismo problema": conviene que
     * agrupe por servicio y causa (p.ej. {@code PAYMENT:stripe:card_declined}) y NO por pedido, porque si
     * no cada pedido afectado mandaría su propio correo.
     */
    public record Alert(AlertKind kind, String title, String detail, String dedupeKey) {
    }

    private final EmailQueueService emailQueue;

    @Value("${nexadrop.alerts.email:jfinol02@gmail.com}")
    private String alertEmail;
    @Value("${nexadrop.alerts.enabled:true}")
    private boolean enabled;
    /** Minutos que se silencia un mismo problema tras avisar de él. */
    @Value("${nexadrop.alerts.cooldown-minutes:30}")
    private long cooldownMinutes;

    private final Map<String, Instant> lastSentByKey = new ConcurrentHashMap<>();

    /**
     * Avisa del fallo si no se ha avisado ya de lo mismo hace poco.
     *
     * <p>Nunca propaga excepciones: un problema enviando el aviso no puede tumbar el flujo que lo
     * originó — sería convertir una incidencia en una caída.
     */
    public void notifyFailure(Alert alert) {
        if (!enabled || alertEmail == null || alertEmail.isBlank()) {
            return;
        }
        if (!shouldSend(alert.dedupeKey())) {
            log.debug("::> [OPS-ALERT] Aviso silenciado (ya notificado) clave={}", alert.dedupeKey());
            return;
        }
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            // OJO: la plantilla resuelve `icon` como "cid:<valor>" (imagen adjunta), así que un emoji
            // dejaría una imagen rota. El aviso no lleva icono; el asunto ya lo identifica.
            vars.put("title", "Fallo de " + alert.kind().label().toLowerCase() + ": " + alert.title());
            vars.put("preheader", alert.title());
            vars.put("body", alert.detail());
            vars.put("footerNote", "Aviso automático de la plataforma. No hace falta responder.");
            emailQueue.enqueue(alertEmail, "[NX036] Fallo de " + alert.kind().label().toLowerCase()
                    + ": " + alert.title(), "emails/notification", vars);
            log.warn("::> [OPS-ALERT] Aviso enviado tipo={} clave={}", alert.kind(), alert.dedupeKey());
        } catch (RuntimeException e) {
            log.error("::> [OPS-ALERT] No se pudo encolar el aviso clave={} causa={}",
                    alert.dedupeKey(), e.getMessage());
        }
    }

    /** ¿Ha pasado ya el silencio de esta clave? Marca el envío en el mismo paso para evitar duplicados. */
    private boolean shouldSend(String dedupeKey) {
        Instant now = Instant.now();
        Instant previous = lastSentByKey.get(dedupeKey);
        if (previous != null && previous.isAfter(now.minus(Duration.ofMinutes(cooldownMinutes)))) {
            return false;
        }
        lastSentByKey.put(dedupeKey, now);
        return true;
    }

    /** Aviso de que el transportista no ha podido crear el envío de un pedido. */
    public void fulfillmentFailed(String orderNumber, String country, int attempts, String error) {
        String detail = "No se ha podido crear el envío del pedido " + orderNumber
                + " (destino " + country + ") tras " + attempts + " intento(s).\n\n"
                + "Motivo: " + CarrierErrorMessage.humanize(error) + "\n\n"
                + "El pedido queda pendiente en la bandeja de incidencias del panel de administración. "
                + "Cuando corrijas la causa, usa «reintentar envío» para volver a lanzarlo."
                + technicalDetail(error);
        // El asunto lleva el PEDIDO, no la causa: es lo que identifica el aviso de un vistazo. La
        // agrupación sí usa la causa, para que una caída del carrier no mande un correo por pedido.
        notifyFailure(new Alert(AlertKind.FULFILLMENT, "no se ha podido crear el envío del pedido " + orderNumber,
                detail, "FULFILLMENT:" + shortCause(error)));
    }

    /** Aviso de que una pasarela de pago ha fallado. */
    public void paymentFailed(String provider, String operation, String reference, String error) {
        String detail = "La pasarela " + provider + " ha fallado al " + operation + ".\n\n"
                + "Referencia: " + (reference != null ? reference : "(sin referencia)") + "\n\n"
                + "Si el fallo persiste, los clientes no podrán completar el pago con este método."
                + technicalDetail(error);
        notifyFailure(new Alert(AlertKind.PAYMENT, "la pasarela " + provider + " ha fallado al " + operation,
                detail, "PAYMENT:" + provider + ":" + operation + ":" + shortCause(error)));
    }

    /**
     * Bloque con el mensaje ORIGINAL del proveedor, separado del texto en español.
     *
     * <p>Va aparte y etiquetado a propósito: el aviso se lee en español, pero si hay que abrir una
     * incidencia con el transportista o la pasarela hace falta su texto exacto, no una traducción.
     */
    private static String technicalDetail(String error) {
        if (error == null || error.isBlank()) {
            return "";
        }
        return "\n\n— Detalle técnico (mensaje original del proveedor) —\n" + error;
    }

    /**
     * Resumen corto y estable de la causa, para agrupar. Se queda con el principio del mensaje porque el
     * final suele traer identificadores irrepetibles (ids, importes) que romperían el agrupamiento.
     */
    public static String shortCause(String error) {
        if (error == null || error.isBlank()) {
            return "desconocido";
        }
        String flat = error.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60);
    }
}
