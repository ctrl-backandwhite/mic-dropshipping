package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethodLabel;
import com.nexaplatform.dropshipping.domain.enums.ShipmentEventMessage;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Emails transaccionales del ciclo de vida del pedido. Encola directamente vía {@link EmailQueueService}
 * (mismo patrón que los emails de auth, que sí se envían — el camino por Kafka quedaba sin renderizar).
 * Todos los envíos van en try/catch para no bloquear el flujo de negocio si el correo falla.
 *
 * <p>Puntos cubiertos (solo los necesarios): pago confirmado (con factura), pedido despachado/en camino
 * (con tracking), entregado y reembolsado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEmailService {

    private final EmailQueueService emailQueue;
    private final InvoiceService invoiceService;

    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String baseUrl;

    /** Pago confirmado → email con la FACTURA del pedido + enlace para descargar el PDF. */
    public void paymentConfirmed(Order o, String email, String locale, String paymentMethod) {
        paymentConfirmed(o, email, locale, paymentMethod, null);
    }

    /**
     * Email de pago confirmado + factura. {@code invoiceCurrency} es la moneda de pago (EUR si se pagó en
     * EUR, USD en otro caso); si es null se usa la del pedido (USD).
     */
    public void paymentConfirmed(Order o, String email, String locale, String paymentMethod, String invoiceCurrency) {
        if (blank(email)) {
            return;
        }
        try {
            String orderUrl = baseUrl + "/orders/" + o.getId();
            String cur = invoiceCurrency != null && !invoiceCurrency.isBlank() ? invoiceCurrency
                    : (o.getCurrency() != null ? o.getCurrency() : "USD");
            Map<String, Object> vars = new HashMap<>(invoiceService.model(o, locale, orderUrl, cur));
            if (paymentMethod != null) {
                // Mostramos el método traducido al idioma del usuario (Tarjeta/Billetera/…), no el código crudo.
                vars.put("paymentMethod", PaymentMethodLabel.localize(paymentMethod, locale));
            }
            vars.put("ctaLabel", InvoiceLabel.CTA_VIEW.of(InvoiceLabel.lang(locale)));
            emailQueue.enqueue(email, String.valueOf(vars.get("subject")), "emails/invoice", vars);
        } catch (RuntimeException e) {
            log.warn("payment-confirmed email failed for order {}: {}", o.getOrderNumber(), e.getMessage());
        }
    }

    /** Pedido despachado / en camino → email con el nº de seguimiento. */
    public void shipped(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        StringBuilder body = new StringBuilder(OrderEmailLabel.SHIPPED_BODY.of(lang, o.getOrderNumber()));
        if (!blank(o.getTrackingNumber())) {
            body.append("<br/>").append(OrderEmailLabel.TRACKING_NUMBER.of(lang)).append("<strong>")
                    .append(o.getTrackingNumber()).append("</strong>");
            if (!blank(o.getCarrier())) {
                body.append(" (").append(o.getCarrier()).append(")");
            }
        }
        notify(email, OrderEmailLabel.SHIPPED_TITLE.of(lang), body.toString(),
                OrderEmailLabel.CTA_TRACK.of(lang), o, "truck-fast", lang);
    }

    /** Pedido entregado. */
    public void delivered(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        notify(email, OrderEmailLabel.DELIVERED_TITLE.of(lang),
                OrderEmailLabel.DELIVERED_BODY.of(lang, o.getOrderNumber()),
                OrderEmailLabel.CTA_VIEW_ORDER.of(lang), o, "box-open", lang);
    }

    /** Reembolso procesado. */
    public void refunded(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        notify(email, OrderEmailLabel.REFUNDED_TITLE.of(lang),
                OrderEmailLabel.REFUNDED_BODY.of(lang, o.getOrderNumber()),
                OrderEmailLabel.CTA_VIEW_ORDER.of(lang), o, "money-bill-transfer", lang);
    }

    /**
     * Notificación por cada cambio de estado del envío en el timeline de tracking (En tránsito, Llegó al
     * país, En reparto…). El estado interno "registrado en Cainiao" NO llega aquí (lo filtra el llamador) y
     * los saltos "en camino"/"entregado" los cubren {@link #shipped}/{@link #delivered}.
     */
    public void trackingUpdate(Order o, String email, String locale, String description, String location) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        String state = ShipmentEventMessage.translate(description, lang);
        StringBuilder body = new StringBuilder(
                OrderEmailLabel.TRACK_BODY.of(lang, o.getOrderNumber()).replace("{state}", state));
        if (!blank(location)) {
            body.append("<br/>").append(OrderEmailLabel.LOCATION.of(lang)).append(localizeLocation(location, locale));
        }
        if (!blank(o.getTrackingNumber())) {
            body.append("<br/>").append(OrderEmailLabel.TRACKING_NUMBER.of(lang)).append("<strong>")
                    .append(o.getTrackingNumber()).append("</strong>");
        }
        notify(email, OrderEmailLabel.TRACK_TITLE.of(lang), body.toString(),
                OrderEmailLabel.CTA_TRACK.of(lang), o, "truck-fast", lang);
    }

    private void notify(String email, String title, String bodyHtml, String ctaLabel, Order o, String icon,
            String lang) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("icon", icon); // nombre del icono FontAwesome (PNG inline por CID); null = sin icono
            vars.put("bodyHtml", bodyHtml);
            vars.put("preheader", title);
            vars.put("ctaUrl", baseUrl + "/orders/" + o.getId());
            vars.put("ctaLabel", ctaLabel);
            vars.put("footer", "NX036 Dropshipping");
            vars.put("footerNote", OrderEmailLabel.AUTO_NOTE.of(lang)); // pie en el idioma del usuario
            emailQueue.enqueue(email, title, "emails/notification", vars);
        } catch (RuntimeException e) {
            log.warn("order email '{}' failed for {}: {}", title, o.getOrderNumber(), e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Convierte los códigos ISO de país de la ubicación a su nombre completo en el idioma del usuario
     * ("ES" → "España", "Shenzhen, CN" → "Shenzhen, China"). Deja el resto del texto tal cual.
     */
    private static String localizeLocation(String location, String locale) {
        if (location == null || location.isBlank()) {
            return location;
        }
        String lang = InvoiceLabel.lang(locale);
        String loc = location.trim();
        if (loc.matches("[A-Za-z]{2}")) {
            return countryName(loc, lang);
        }
        int comma = loc.lastIndexOf(',');
        if (comma > 0) {
            String tail = loc.substring(comma + 1).trim();
            if (tail.matches("[A-Za-z]{2}")) {
                return loc.substring(0, comma).trim() + ", " + countryName(tail, lang);
            }
        }
        return loc;
    }

    private static String countryName(String code, String lang) {
        try {
            String name = new Locale("", code.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.forLanguageTag(lang));
            return name == null || name.isBlank() || name.equalsIgnoreCase(code) ? code : name;
        } catch (RuntimeException e) {
            return code;
        }
    }
}
