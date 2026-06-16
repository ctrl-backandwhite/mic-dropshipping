package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Genera la factura de un pedido: un modelo de plantilla (i18n) que se renderiza como HTML (para el
 * cuerpo del email de pago) y como PDF (descargable). Reutiliza el {@link TemplateEngine} de Thymeleaf
 * y {@code emails/invoice.html}; el PDF se produce con openhtmltopdf a partir del mismo HTML.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final TemplateEngine templateEngine;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Construye el modelo de la factura (textos i18n + datos del pedido) para la plantilla. */
    public Map<String, Object> model(Order o, String locale, String downloadUrl) {
        boolean es = locale == null || locale.toLowerCase(Locale.ROOT).startsWith("es");
        String cur = o.getCurrency() != null ? o.getCurrency() : "USD";

        List<Map<String, Object>> items = new ArrayList<>();
        if (o.getItems() != null) {
            for (OrderItem it : o.getItems()) {
                items.add(Map.of("title", it.getTitleSnapshot() != null ? it.getTitleSnapshot() : "—", "sku",
                        it.getSkuSnapshot() != null ? it.getSkuSnapshot() : "", "qty", it.getQuantity(), "unit",
                        money(it.getUnitPriceCents(), cur), "lineTotal", money(it.getLineTotalCents(), cur)));
            }
        }
        String city = join(o.getShippingCity(), o.getShippingState(), o.getShippingPostalCode());
        Instant when = o.getPlacedAt() != null ? o.getPlacedAt() : o.getCreatedAt();

        Map<String, Object> m = new java.util.HashMap<>();
        m.put("subject", (es ? "Factura " : "Invoice ") + o.getOrderNumber());
        m.put("title", es ? "Pago confirmado" : "Payment confirmed");
        m.put("intro", es ? "Gracias por tu compra. Aquí tienes la factura de tu pedido."
                : "Thank you for your purchase. Here is the invoice for your order.");
        m.put("preheader", (es ? "Factura " : "Invoice ") + o.getOrderNumber());
        m.put("labelInvoice", es ? "Factura" : "Invoice");
        m.put("labelShipTo", es ? "Enviar a" : "Ship to");
        m.put("labelMethod", es ? "Método de pago" : "Payment method");
        m.put("orderNumber", o.getOrderNumber());
        m.put("invoiceDate", when != null ? DATE.format(when.atZone(ZoneId.systemDefault())) : "");
        m.put("paymentMethod", null);
        m.put("shipName", nz(o.getShippingFullName()));
        m.put("shipLine1", nz(o.getShippingLine1()));
        m.put("shipCityLine", city);
        m.put("shipCountry", nz(o.getShippingCountry()));
        m.put("colItem", es ? "Artículo" : "Item");
        m.put("colQty", es ? "Cant." : "Qty");
        m.put("colPrice", es ? "Precio" : "Price");
        m.put("colTotal", "Total");
        m.put("items", items);
        m.put("labelSubtotal", es ? "Subtotal" : "Subtotal");
        m.put("labelShipping", es ? "Envío" : "Shipping");
        m.put("labelTax", es ? "Impuestos" : "Tax");
        m.put("labelTotal", "Total");
        m.put("subtotal", money(o.getSubtotalCents(), cur));
        m.put("shipping", money(o.getShippingCents(), cur));
        m.put("tax", money(o.getTaxCents(), cur));
        m.put("total", money(o.getTotalCents(), cur));
        m.put("ctaUrl", downloadUrl);
        m.put("ctaLabel", es ? "Descargar factura (PDF)" : "Download invoice (PDF)");
        m.put("footer", es
                ? "NX036 Dropshipping · Este es un comprobante de tu pedido. Conserva esta factura."
                : "NX036 Dropshipping · This is your order receipt. Please keep this invoice.");
        return m;
    }

    /** Renderiza la factura como HTML (cuerpo del email). */
    public String renderHtml(Order o, String locale, String downloadUrl) {
        Context ctx = new Context();
        model(o, locale, downloadUrl).forEach(ctx::setVariable);
        return templateEngine.process("emails/invoice", ctx);
    }

    /** Renderiza la factura como PDF (descargable). */
    public byte[] renderPdf(Order o, String locale) {
        String html = renderHtml(o, locale, null); // sin CTA en el PDF
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();
            return os.toByteArray();
        } catch (Exception e) {
            log.error("No se pudo generar el PDF de la factura del pedido {}", o.getOrderNumber(), e);
            throw new IllegalStateException("Invoice PDF generation failed: " + e.getMessage(), e);
        }
    }

    private static String money(int cents, String currency) {
        BigDecimal v = BigDecimal.valueOf(cents).movePointLeft(2);
        return ("USD".equalsIgnoreCase(currency) ? "$" : currency + " ") + v.toPlainString();
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }
}
