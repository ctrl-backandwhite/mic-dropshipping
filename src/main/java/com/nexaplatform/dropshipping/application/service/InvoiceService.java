package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final CurrencyRateService currencyRateService;

    // Datos fiscales del EMISOR (la plataforma) para que la factura sea un documento legal.
    // Se configuran por entorno (nunca se inventan); si legal-name está vacío, el bloque no se pinta.
    @Value("${nexadrop.invoice.issuer.legal-name:}")
    private String issuerLegalName;
    @Value("${nexadrop.invoice.issuer.tax-id:}")
    private String issuerTaxId;
    @Value("${nexadrop.invoice.issuer.address:}")
    private String issuerAddress;
    @Value("${nexadrop.invoice.issuer.city-line:}")
    private String issuerCityLine;
    @Value("${nexadrop.invoice.issuer.country:}")
    private String issuerCountry;
    @Value("${nexadrop.invoice.issuer.email:}")
    private String issuerEmail;
    @Value("${nexadrop.invoice.issuer.registry:}")
    private String issuerRegistry;
    @Value("${nexadrop.invoice.legal-note:}")
    private String legalNote;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Modelo de factura en la moneda del pedido (USD canónico). */
    public Map<String, Object> model(Order o, String locale, String downloadUrl) {
        return model(o, locale, downloadUrl, o.getCurrency() != null ? o.getCurrency() : "USD");
    }

    /**
     * Construye el modelo de la factura en la moneda indicada (la de pago: EUR si se pagó en EUR, USD en
     * otro caso). Los importes del pedido están en céntimos USD canónicos; se convierten a la moneda de la
     * factura con la tasa del día (2 decimales, redondeo arriba).
     */
    public Map<String, Object> model(Order o, String locale, String downloadUrl, String invoiceCurrency) {
        boolean es = locale == null || locale.toLowerCase(Locale.ROOT).startsWith("es");
        String cur = invoiceCurrency != null && !invoiceCurrency.isBlank() ? invoiceCurrency.toUpperCase() : "USD";

        // Precio por línea en la moneda de la factura (2 dec hacia arriba), igual que el carrito y el cobro.
        // El subtotal/total se SUMAN de las líneas para que la factura sea internamente coherente y coincida
        // con lo cobrado (no se convierte el total una sola vez).
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal subtotalDisp = BigDecimal.ZERO;
        if (o.getItems() != null) {
            for (OrderItem it : o.getItems()) {
                BigDecimal unit = conv(it.getUnitPriceCents(), cur);
                BigDecimal lineDisp = unit.multiply(BigDecimal.valueOf(it.getQuantity()));
                subtotalDisp = subtotalDisp.add(lineDisp);
                items.add(Map.of("title", it.getTitleSnapshot() != null ? it.getTitleSnapshot() : "—", "sku",
                        it.getSkuSnapshot() != null ? it.getSkuSnapshot() : "", "variant",
                        it.getVariantName() != null ? it.getVariantName() : "", "qty", it.getQuantity(), "unit",
                        fmt(unit, cur), "lineTotal", fmt(lineDisp, cur), "image",
                        it.getImageUrlSnapshot() != null ? it.getImageUrlSnapshot() : ""));
            }
        }
        BigDecimal shippingDisp = conv(o.getShippingCents(), cur);
        BigDecimal taxDisp = conv(o.getTaxCents(), cur);
        BigDecimal totalDisp = subtotalDisp.add(shippingDisp).add(taxDisp);
        // Base imponible = subtotal + envío (lo gravado por el IVA). Tipo efectivo derivado de los
        // importes para mostrar "IVA (X%)" sin depender de un campo de tipo separado.
        BigDecimal baseDisp = subtotalDisp.add(shippingDisp);
        int vatRate = baseDisp.signum() > 0
                ? taxDisp.multiply(BigDecimal.valueOf(100)).divide(baseDisp, 0, RoundingMode.HALF_UP).intValue()
                : 0;
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
        m.put("labelBase", es ? "Base imponible" : "Taxable base");
        m.put("labelTax", (es ? "IVA" : "VAT") + " (" + vatRate + "%)");
        m.put("labelTotal", "Total");
        m.put("subtotal", fmt(subtotalDisp, cur));
        m.put("shipping", fmt(shippingDisp, cur));
        m.put("base", fmt(baseDisp, cur));
        m.put("tax", fmt(taxDisp, cur));
        m.put("total", fmt(totalDisp, cur));

        // Bloque fiscal del EMISOR (solo si está configurado: no inventamos datos legales).
        boolean hasIssuer = issuerLegalName != null && !issuerLegalName.isBlank();
        m.put("hasIssuer", hasIssuer);
        m.put("labelIssuer", es ? "Emisor" : "Issuer");
        m.put("labelBillTo", es ? "Facturar a" : "Bill to");
        m.put("labelTaxId", es ? "NIF/CIF" : "Tax ID");
        m.put("issuerLegalName", nz(issuerLegalName));
        m.put("issuerTaxId", nz(issuerTaxId));
        m.put("issuerAddress", nz(issuerAddress));
        m.put("issuerCityLine", nz(issuerCityLine));
        m.put("issuerCountry", nz(issuerCountry));
        m.put("issuerEmail", nz(issuerEmail));
        m.put("issuerRegistry", nz(issuerRegistry));
        m.put("legalNote", nz(legalNote));
        // Color del lienzo (fuera del cuadro): lavanda en el email; el PDF lo sobreescribe a blanco.
        m.put("bodyBg", "#F4F1FB");
        m.put("ctaUrl", downloadUrl);
        m.put("ctaLabel", es ? "Descargar factura (PDF)" : "Download invoice (PDF)");
        m.put("footer", es
                ? "NX036 Dropshipping · Este es un comprobante de tu pedido. Conserva esta factura."
                : "NX036 Dropshipping · This is your order receipt. Please keep this invoice.");
        return m;
    }

    /** Renderiza la factura como HTML (cuerpo del email) en la moneda del pedido. */
    public String renderHtml(Order o, String locale, String downloadUrl) {
        return renderHtml(o, locale, downloadUrl, o.getCurrency() != null ? o.getCurrency() : "USD");
    }

    /** Renderiza la factura HTML en la moneda indicada. */
    public String renderHtml(Order o, String locale, String downloadUrl, String currency) {
        Context ctx = new Context();
        model(o, locale, downloadUrl, currency).forEach(ctx::setVariable);
        return templateEngine.process("emails/invoice", ctx);
    }

    /** Renderiza la factura PDF en la moneda del pedido. */
    public byte[] renderPdf(Order o, String locale) {
        return renderPdf(o, locale, o.getCurrency() != null ? o.getCurrency() : "USD");
    }

    /** Renderiza la factura PDF en la moneda indicada (la de pago: EUR si se pagó en EUR, USD en otro caso). */
    public byte[] renderPdf(Order o, String locale, String currency) {
        // El PDF es un documento descargable → lienzo BLANCO (no el lavanda del email). Sin CTA.
        Map<String, Object> m = model(o, locale, null, currency);
        m.put("bodyBg", "#ffffff");
        // El nombre del producto se muestra COMPLETO (la celda hace wrap); antes se truncaba a 40
        // caracteres pero la factura debe llevar la descripción íntegra del artículo.
        Context ctx = new Context();
        m.forEach(ctx::setVariable);
        String html = templateEngine.process("emails/invoice", ctx);
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

    /** Acorta un texto a {@code max} caracteres añadiendo "…" si lo supera. */
    private static String ellipsis(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max).trim() + "…" : s;
    }

    /** Convierte céntimos USD canónicos a {@code currency} (2 decimales hacia arriba). */
    private BigDecimal conv(int usdCents, String currency) {
        BigDecimal usd = BigDecimal.valueOf(usdCents).movePointLeft(2);
        return "USD".equalsIgnoreCase(currency) ? usd.setScale(2, java.math.RoundingMode.UP)
                : currencyRateService.usdTo(usd, currency);
    }

    /** Formatea un importe ya convertido con el símbolo de la moneda. */
    private String fmt(BigDecimal v, String currency) {
        String symbol = currencyRateService.symbolOf(currency);
        boolean prefixSymbol = symbol != null && !symbol.equalsIgnoreCase(currency);
        return prefixSymbol ? symbol + v.toPlainString() : currency + " " + v.toPlainString();
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
