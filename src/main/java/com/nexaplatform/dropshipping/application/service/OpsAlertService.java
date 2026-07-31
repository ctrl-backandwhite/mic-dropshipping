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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * A dónde van los avisos de incidencia (envío no creado, cobro fallido). El valor por defecto era un
     * correo PERSONAL escrito en el código, que se publica con cada copia del repositorio y ata las
     * alertas de producción a la cuenta de una persona concreta: el día que esa persona no esté, las
     * incidencias dejan de leerse sin que nadie se entere. Se configura por entorno.
     */
    @Value("${nexadrop.alerts.email:support@nx036.com}")
    private String alertEmail;
    @Value("${nexadrop.alerts.enabled:true}")
    private boolean enabled;
    /** Minutos que se silencia un mismo problema tras avisar de él. */
    @Value("${nexadrop.alerts.cooldown-minutes:30}")
    private long cooldownMinutes;

    /** Código de error del proveedor (YunExpress los da de 8 dígitos): identifica la causa sin el pedido. */
    private static final Pattern PROVIDER_CODE = Pattern.compile("\\b\\d{8}\\b");

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
        // Purga de entradas ya caducadas: el registro vive en memoria y no puede crecer sin techo.
        lastSentByKey.values().removeIf(sent -> sent.isBefore(now.minus(Duration.ofMinutes(cooldownMinutes))));
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
                detail, "FULFILLMENT:" + causeKey(error)));
    }

    /** Aviso de que una pasarela de pago ha fallado. */
    public void paymentFailed(String provider, String operation, String reference, String error) {
        String detail = "La pasarela " + provider + " ha fallado al " + operation + ".\n\n"
                + "Referencia: " + (reference != null ? reference : "(sin referencia)") + "\n\n"
                + "Si el fallo persiste, los clientes no podrán completar el pago con este método."
                + technicalDetail(error);
        notifyFailure(new Alert(AlertKind.PAYMENT, "la pasarela " + provider + " ha fallado al " + operation,
                detail, "PAYMENT:" + provider + ":" + operation + ":" + causeKey(error)));
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
     * Resumen corto de la causa, para el ASUNTO del correo. No sirve para agrupar: los mensajes del
     * proveedor llevan el número de pedido, así que dos fallos idénticos producen resúmenes distintos.
     * Para agrupar está {@link #causeKey(String)}.
     */
    public static String shortCause(String error) {
        if (error == null || error.isBlank()) {
            return "desconocido";
        }
        String flat = error.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60);
    }

    /**
     * Clave con la que se decide si dos fallos son "el mismo problema".
     *
     * <p>Tiene que ser INDEPENDIENTE del pedido concreto. Usar el principio del mensaje no vale: el
     * proveedor lo devuelve como "rechazó el envío del pedido NX-1785149919-8710: 02039171…", así que
     * cada pedido generaba su propia clave, el silencio no agrupaba nada y una caída del transportista
     * llenaba el buzón con un correo por pedido — lo contrario de lo que se buscaba. Además el registro
     * de claves crecía sin límite.
     *
     * <p>Se agrupa por el CÓDIGO de error del proveedor cuando aparece (8 dígitos); si no hay código, por
     * el mensaje con los identificadores y las cifras neutralizados.
     */
    public static String causeKey(String error) {
        if (error == null || error.isBlank()) {
            return "desconocido";
        }
        Matcher code = PROVIDER_CODE.matcher(error);
        if (code.find()) {
            return code.group();
        }
        String normalized = error.replaceAll("\\s+", " ")
                .replaceAll("[A-Z]{2}-\\d[\\w-]*", "#")   // referencias tipo NX-1785149919-8710
                .replaceAll("\\d+", "#")                  // importes, pesos, ids sueltos
                .trim();
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }
}
