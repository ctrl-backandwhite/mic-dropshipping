package com.nexaplatform.dropshipping.application.service;

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

    private static boolean es(String locale) {
        return locale == null || locale.toLowerCase(Locale.ROOT).startsWith("es");
    }

    /** Pago confirmado → email con la FACTURA del pedido + enlace para descargar el PDF. */
    public void paymentConfirmed(Order o, String email, String locale, String paymentMethod) {
        if (blank(email)) {
            return;
        }
        try {
            String orderUrl = baseUrl + "/orders/" + o.getId();
            Map<String, Object> vars = new HashMap<>(invoiceService.model(o, locale, orderUrl));
            if (paymentMethod != null) {
                vars.put("paymentMethod", paymentMethod);
            }
            vars.put("ctaLabel", es(locale) ? "Ver pedido y factura" : "View order & invoice");
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
        boolean es = es(locale);
        StringBuilder body = new StringBuilder(es
                ? "Tu pedido <strong>" + o.getOrderNumber() + "</strong> ha sido despachado y está en camino."
                : "Your order <strong>" + o.getOrderNumber() + "</strong> has been shipped and is on its way.");
        if (!blank(o.getTrackingNumber())) {
            body.append(es ? "<br/>Nº de seguimiento: <strong>" : "<br/>Tracking number: <strong>")
                    .append(o.getTrackingNumber()).append("</strong>");
            if (!blank(o.getCarrier())) {
                body.append(es ? " (" : " (").append(o.getCarrier()).append(")");
            }
        }
        notify(email, es ? "Tu pedido va en camino 🚚" : "Your order is on its way 🚚", body.toString(),
                es ? "Seguir mi pedido" : "Track my order", o);
    }

    /** Pedido entregado. */
    public void delivered(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        boolean es = es(locale);
        notify(email, es ? "Tu pedido ha sido entregado 📦" : "Your order has been delivered 📦",
                es ? "Tu pedido <strong>" + o.getOrderNumber() + "</strong> ha sido entregado. ¡Esperamos que lo disfrutes!"
                        : "Your order <strong>" + o.getOrderNumber() + "</strong> has been delivered. We hope you enjoy it!",
                es ? "Ver pedido" : "View order", o);
    }

    /** Reembolso procesado. */
    public void refunded(Order o, String email, String locale) {
        if (blank(email)) {
            return;
        }
        boolean es = es(locale);
        notify(email, es ? "Reembolso procesado" : "Refund processed",
                es ? "Hemos procesado el reembolso de tu pedido <strong>" + o.getOrderNumber()
                        + "</strong>. El importe se devolverá a tu método de pago original."
                        : "We have processed the refund for your order <strong>" + o.getOrderNumber()
                                + "</strong>. The amount will be returned to your original payment method.",
                es ? "Ver pedido" : "View order", o);
    }

    private void notify(String email, String title, String bodyHtml, String ctaLabel, Order o) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("bodyHtml", bodyHtml);
            vars.put("preheader", title);
            vars.put("ctaUrl", baseUrl + "/orders/" + o.getId());
            vars.put("ctaLabel", ctaLabel);
            vars.put("footer", "NX036 Dropshipping");
            emailQueue.enqueue(email, title, "emails/notification", vars);
        } catch (RuntimeException e) {
            log.warn("order email '{}' failed for {}: {}", title, o.getOrderNumber(), e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
