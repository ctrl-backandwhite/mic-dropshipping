package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.BrandTagline;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.OrderEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethodLabel;
import com.nexaplatform.dropshipping.domain.enums.ShipmentEventMessage;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String ORDERS = "/orders/";
    private static final String BR = "<br/>";

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
            String orderUrl = baseUrl + ORDERS + o.getId();
            String cur = Texts.firstNonBlankOr("USD", invoiceCurrency, o.getCurrency());
            Map<String, Object> vars = new HashMap<>(invoiceService.model(o, locale, orderUrl, cur));
            if (paymentMethod != null) {
                // Mostramos el método traducido al idioma del usuario (Tarjeta/Billetera/…), no el código crudo.
                vars.put("paymentMethod", PaymentMethodLabel.localize(paymentMethod, locale));
            }
            vars.put("ctaLabel", InvoiceLabel.CTA_VIEW.of(InvoiceLabel.lang(locale)));
            // Las fotos de producto van ADJUNTAS al correo (cid:), no por URL: la URL del storage es
            // localhost en local —inalcanzable para el proxy de Gmail— y Outlook/Apple Mail bloquean las
            // imágenes remotas por defecto. Se saca del modelo para no pasarla a la plantilla.
            @SuppressWarnings("unchecked")
            Map<String, String> inlineImages = (Map<String, String>) vars
                    .remove(InvoiceService.INLINE_IMAGES_KEY);
            emailQueue.enqueue(email, null, String.valueOf(vars.get("subject")), "emails/invoice", vars,
                    inlineImages != null ? inlineImages : Map.of());
        } catch (RuntimeException e) {
            log.warn("payment-confirmed email failed for order {}: {}", o.getOrderNumber(), e.getMessage());
        }
    }

    /** Pedido despachado / en camino → email con el nº de seguimiento. */
    /**
     * Pedido registrado y pendiente de pago (tarjeta/PayPal/USDT). El pago con saldo no pasa por aquí:
     * cobra en el acto y su primer correo ya es la factura.
     */
    public void placedAwaitingPayment(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        notify(email, o, new Notice(OrderEmailLabel.PLACED_TITLE.of(lang),
                OrderEmailLabel.PLACED_BODY.of(lang, o.getOrderNumber()),
                OrderEmailLabel.CTA_VIEW_ORDER.of(lang), "clipboard-check", lang));
    }

    public void shipped(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        StringBuilder body = new StringBuilder(OrderEmailLabel.SHIPPED_BODY.of(lang, o.getOrderNumber()));
        if (!blank(o.getTrackingNumber())) {
            body.append(BR).append(OrderEmailLabel.TRACKING_NUMBER.of(lang)).append("<strong>")
                    .append(o.getTrackingNumber()).append("</strong>");
            if (!blank(o.getCarrier())) {
                body.append(" (").append(o.getCarrier()).append(")");
            }
        }
        notify(email, o, new Notice(OrderEmailLabel.SHIPPED_TITLE.of(lang), body.toString(),
                OrderEmailLabel.CTA_TRACK.of(lang), "truck-fast", lang));
    }

    /** Pedido entregado. */
    public void delivered(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        notify(email, o, new Notice(OrderEmailLabel.DELIVERED_TITLE.of(lang),
                OrderEmailLabel.DELIVERED_BODY.of(lang, o.getOrderNumber()),
                OrderEmailLabel.CTA_VIEW_ORDER.of(lang), "box-open", lang));
    }

    /** Reembolso procesado. Retrocompat: reembolso al método original, moneda del pedido. */
    public void refunded(Order o, String email, String locale) {
        refunded(o, email, locale, false, null, null);
    }

    /**
     * Reembolso procesado, con un bloque de detalle profesional (nº de pedido, fecha, importe, artículos y
     * destino del reembolso) en el idioma del usuario.
     *
     * @param toWallet        true si el reembolso se acreditó al saldo (inmediato); false = método original.
     * @param settlementCcy   moneda en la que se cobró/reembolsa (EUR/USD/USDT); null → moneda del pedido.
     * @param paymentMethod   método de pago original (CARD/PAYPAL/WALLET/USDT) para describir el destino.
     */
    public void refunded(Order o, String email, String locale, boolean toWallet, String settlementCcy,
            String paymentMethod) {
        if (blank(email)) {
            return;
        }
        String lang = InvoiceLabel.lang(locale);
        // La divisa del correo es la que se LIQUIDÓ; si el cobro no la fijó, la del pedido.
        String cur = Texts.firstNonBlankOr("USD", settlementCcy, o.getCurrency());

        List<String[]> details = new ArrayList<>();
        details.add(new String[] { OrderEmailLabel.REFUND_L_ORDER.of(lang), o.getOrderNumber() });
        String date = refundDate(o);
        if (!blank(date)) {
            details.add(new String[] { OrderEmailLabel.REFUND_L_DATE.of(lang), date });
        }
        String amount = refundAmount(o, locale, cur);
        if (!blank(amount)) {
            details.add(new String[] { OrderEmailLabel.REFUND_L_AMOUNT.of(lang), amount });
        }
        if (o.getItems() != null && !o.getItems().isEmpty()) {
            details.add(new String[] { OrderEmailLabel.REFUND_L_ITEMS.of(lang),
                    String.valueOf(o.getItems().size()) });
        }
        details.add(new String[] { OrderEmailLabel.REFUND_L_DEST.of(lang),
                refundDestination(lang, toWallet, paymentMethod) });

        notify(email, o, new Notice(OrderEmailLabel.REFUNDED_TITLE.of(lang),
                OrderEmailLabel.REFUNDED_BODY.of(lang, o.getOrderNumber()),
                OrderEmailLabel.CTA_VIEW_ORDER.of(lang), "money-bill-transfer", lang, details));
    }

    /** Texto del destino del reembolso: billetera (inmediato) o el método original (tarjeta/PayPal). */
    private static String refundDestination(String lang, boolean toWallet, String paymentMethod) {
        if (toWallet) {
            return OrderEmailLabel.REFUND_DEST_WALLET.of(lang);
        }
        String m = paymentMethod == null ? "" : paymentMethod.toUpperCase(Locale.ROOT);
        if ("CARD".equals(m)) {
            return OrderEmailLabel.REFUND_DEST_CARD.of(lang);
        }
        if ("PAYPAL".equals(m)) {
            return OrderEmailLabel.REFUND_DEST_PAYPAL.of(lang);
        }
        // Wallet como método original (o USDT/desconocido): se acredita al saldo, inmediato.
        return OrderEmailLabel.REFUND_DEST_WALLET.of(lang);
    }

    /** Fecha del reembolso (cancelledAt, o ahora) formateada según la factura. */
    private String refundDate(Order o) {
        Instant when = o.getCancelledAt() != null ? o.getCancelledAt() : Instant.now();
        return invoiceService.formatDate(when);
    }

    /** Importe reembolsado ya formateado en la moneda cobrada (= exactamente lo que se devuelve). */
    private String refundAmount(Order o, String locale, String cur) {
        try {
            Object total = invoiceService.model(o, locale, baseUrl + ORDERS + o.getId(), cur).get("total");
            return total != null ? String.valueOf(total) : null;
        } catch (RuntimeException e) {
            log.warn("refund amount formatting failed for {}: {}", o.getOrderNumber(), e.getMessage());
            return null;
        }
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
            body.append(BR).append(OrderEmailLabel.LOCATION.of(lang)).append(localizeLocation(location, locale));
        }
        if (!blank(o.getTrackingNumber())) {
            body.append(BR).append(OrderEmailLabel.TRACKING_NUMBER.of(lang)).append("<strong>")
                    .append(o.getTrackingNumber()).append("</strong>");
        }
        notify(email, o, new Notice(OrderEmailLabel.TRACK_TITLE.of(lang), body.toString(),
                OrderEmailLabel.CTA_TRACK.of(lang), "truck-fast", lang));
    }

    /**
     * Contenido variable de un aviso de pedido. Va agrupado en un record porque los textos viajan
     * siempre juntos y todos son String: sueltos eran ocho parámetros consecutivos del mismo tipo, con
     * lo que intercambiar dos por error compilaba igual y el fallo solo se veía en el correo enviado.
     */
    private record Notice(String title, String bodyHtml, String ctaLabel, String icon, String lang,
            List<String[]> details) {

        /** Aviso sin bloque de detalle (envío, entrega y tracking); solo el reembolso lo lleva. */
        Notice(String title, String bodyHtml, String ctaLabel, String icon, String lang) {
            this(title, bodyHtml, ctaLabel, icon, lang, null);
        }
    }

    private void notify(String email, Order o, Notice notice) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", notice.title());
            // nombre del icono FontAwesome (PNG inline por CID); null = sin icono
            vars.put("icon", notice.icon());
            vars.put("bodyHtml", notice.bodyHtml());
            if (notice.details() != null && !notice.details().isEmpty()) {
                vars.put("details", notice.details()); // bloque etiqueta/valor con el resumen (pedido/reembolso)
            }
            vars.put("preheader", notice.title());
            vars.put("ctaUrl", baseUrl + ORDERS + o.getId());
            vars.put("ctaLabel", notice.ctaLabel());
            vars.put("footer", "NX036");
            vars.put("footerNote", OrderEmailLabel.AUTO_NOTE.of(notice.lang())); // pie en el idioma del usuario
            // El descriptor de la cabecera, en el mismo idioma: «NX036 · Moda y complementos». Si el
            // pedido entró por una tienda conectada, quien lo recibe es un socio de integración y para
            // él sí manda la palabra «dropshipping»: es el servicio que tiene contratado.
            boolean socio = "INTEGRATION".equalsIgnoreCase(o.getSource());
            vars.put("tagline", socio ? BrandTagline.of(UserRole.PARTNER, notice.lang())
                    : BrandTagline.of(notice.lang()));
            emailQueue.enqueue(email, notice.title(), "emails/notification", vars);
        } catch (RuntimeException e) {
            log.warn("order email '{}' failed for {}: {}", notice.title(), o.getOrderNumber(), e.getMessage());
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
