package com.nexaplatform.dropshipping.application.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
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
import java.util.Base64;
import java.util.EnumMap;
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
    private final PaymentJpaRepositoryAdapter paymentRepository;

    /** Importe realmente cobrado (settlement) del pago satisfactorio si coincide con la moneda de la factura. */
    private BigDecimal settlementTotal(java.util.UUID orderId, String ccy) {
        if (orderId == null || ccy == null) {
            return null;
        }
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .filter(p -> p.getSettlementAmount() != null && ccy.equalsIgnoreCase(p.getSettlementCurrency()))
                .map(p -> p.getSettlementAmount())
                .findFirst().orElse(null);
    }

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
    // Base pública para el QR de verificación de la factura (apunta al endpoint público de verificación).
    @Value("${nexadrop.invoice.verify-base-url:http://localhost:18082}")
    private String verifyBaseUrl;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Fecha formateada con el mismo patrón que la factura (para reutilizar en emails). */
    public String formatDate(java.time.Instant when) {
        return when != null ? DATE.format(when.atZone(ZoneId.systemDefault())) : "";
    }

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
        // Pedido ya pagado: la factura muestra EXACTAMENTE lo cobrado (settlement), no la re-conversión a la
        // tasa actual (que deriva con el tiempo). Escalamos el desglose (conversión lineal) para que cuadre.
        BigDecimal settle = settlementTotal(o.getId(), cur);
        if (settle != null && totalDisp.signum() > 0) {
            BigDecimal f = settle.divide(totalDisp, 10, RoundingMode.HALF_UP);
            subtotalDisp = subtotalDisp.multiply(f).setScale(2, RoundingMode.HALF_UP);
            shippingDisp = shippingDisp.multiply(f).setScale(2, RoundingMode.HALF_UP);
            totalDisp = settle.setScale(2, RoundingMode.HALF_UP);
            taxDisp = totalDisp.subtract(subtotalDisp).subtract(shippingDisp);
        }
        // Base imponible = subtotal + envío (lo gravado por el IVA). Tipo efectivo derivado de los
        // importes para mostrar "IVA (X%)" sin depender de un campo de tipo separado.
        BigDecimal baseDisp = subtotalDisp.add(shippingDisp);
        int vatRate = baseDisp.signum() > 0
                ? taxDisp.multiply(BigDecimal.valueOf(100)).divide(baseDisp, 0, RoundingMode.HALF_UP).intValue()
                : 0;
        String city = join(o.getShippingCity(), o.getShippingState(), o.getShippingPostalCode());
        Instant when = o.getPlacedAt() != null ? o.getPlacedAt() : o.getCreatedAt();

        String lang = InvoiceLabel.lang(locale); // idioma de navegación → la factura se emite en él
        Map<String, Object> m = new java.util.HashMap<>();
        // Por defecto el modelo es para el EMAIL (lleva saludo/CTA). renderPdf lo pone a true para
        // ocultar lo propio del email y dejar un documento de factura limpio y profesional.
        m.put("pdf", false);
        m.put("subject", InvoiceLabel.INVOICE.of(lang) + " " + o.getOrderNumber());
        m.put("title", InvoiceLabel.TITLE_PAID.of(lang));
        m.put("icon", "circle-check"); // icono FontAwesome (PNG inline por CID) junto al saludo del email
        m.put("intro", InvoiceLabel.INTRO.of(lang));
        m.put("preheader", InvoiceLabel.INVOICE.of(lang) + " " + o.getOrderNumber());
        m.put("labelInvoice", InvoiceLabel.INVOICE.of(lang));
        m.put("labelShipTo", InvoiceLabel.BILL_TO.of(lang));
        m.put("labelMethod", InvoiceLabel.PAYMENT_METHOD.of(lang));
        m.put("orderNumber", o.getOrderNumber());
        m.put("invoiceDate", when != null ? DATE.format(when.atZone(ZoneId.systemDefault())) : "");
        m.put("labelIssueDate", InvoiceLabel.ISSUE_DATE.of(lang));
        m.put("paymentMethod", null);
        // Estado del pedido como insignia (PAGADA en verde si está pagado/cumplido).
        String statusName = o.getStatus() != null ? o.getStatus().name() : "";
        boolean paid = statusName.equals("PAID") || statusName.equals("SHIPPED")
                || statusName.equals("DELIVERED") || statusName.equals("FULFILLED") || statusName.equals("COMPLETED");
        m.put("statusPaid", paid);
        m.put("statusLabel", statusName.isEmpty() ? ""
                : (paid ? InvoiceLabel.PAID.of(lang) : InvoiceLabel.PENDING.of(lang)));
        m.put("shipName", nz(o.getShippingFullName()));
        m.put("shipEmail", nz(o.getShippingEmail()));
        m.put("shipPhone", nz(o.getShippingPhone()));
        m.put("shipLine1", nz(o.getShippingLine1()));
        m.put("shipCityLine", city);
        m.put("shipCountry", countryName(o.getShippingCountry(), locale));
        m.put("colItem", InvoiceLabel.DESCRIPTION.of(lang));
        m.put("colQty", InvoiceLabel.QTY.of(lang));
        m.put("colPrice", InvoiceLabel.PRICE.of(lang));
        m.put("colTotal", InvoiceLabel.TOTAL.of(lang));
        m.put("items", items);
        m.put("labelSubtotal", InvoiceLabel.SUBTOTAL.of(lang));
        m.put("labelShipping", InvoiceLabel.SHIPPING.of(lang));
        m.put("labelTax", InvoiceLabel.VAT.of(lang) + " (" + vatRate + "%)");
        m.put("labelTotal", InvoiceLabel.TOTAL.of(lang));
        m.put("subtotal", fmt(subtotalDisp, cur));
        m.put("shipping", fmt(shippingDisp, cur));
        m.put("tax", fmt(taxDisp, cur));
        m.put("total", fmt(totalDisp, cur));

        // Bloque fiscal del EMISOR (solo si está configurado: no inventamos datos legales).
        boolean hasIssuer = issuerLegalName != null && !issuerLegalName.isBlank();
        m.put("hasIssuer", hasIssuer);
        m.put("labelIssuer", es ? "Emisor" : "Issuer");
        m.put("labelBillTo", InvoiceLabel.BILL_TO.of(lang));
        m.put("labelTaxId", InvoiceLabel.TAX_ID.of(lang));
        m.put("issuerLegalName", nz(issuerLegalName));
        m.put("issuerTaxId", nz(issuerTaxId));
        m.put("issuerAddress", nz(issuerAddress));
        m.put("issuerCityLine", nz(issuerCityLine));
        m.put("issuerCountry", nz(issuerCountry));
        m.put("issuerEmail", nz(issuerEmail));
        m.put("issuerRegistry", nz(issuerRegistry));
        m.put("legalNote", nz(legalNote));

        // Línea legal del pie: razón social · CIF · email · nº de factura (lo que esté configurado).
        StringBuilder legal = new StringBuilder();
        if (hasIssuer) {
            legal.append(issuerLegalName);
            if (issuerTaxId != null && !issuerTaxId.isBlank()) {
                legal.append(" · ").append(InvoiceLabel.TAX_ID_PREFIX.of(lang)).append(issuerTaxId);
            }
            if (issuerEmail != null && !issuerEmail.isBlank()) {
                legal.append(" · ").append(issuerEmail);
            }
        }
        legal.append(legal.length() > 0 ? " · " : "").append(o.getOrderNumber());
        m.put("footerLegal", legal.toString());

        // QR de verificación: codifica la URL pública de verificación de esta factura.
        String base = verifyBaseUrl != null ? verifyBaseUrl.replaceAll("/+$", "") : "";
        String verifyUrl = base + "/api/v1/storefront/invoices/" + o.getOrderNumber() + "/verify";
        m.put("verifyUrl", verifyUrl);
        m.put("qr", qrDataUri(verifyUrl));
        m.put("labelVerify", InvoiceLabel.VERIFY.of(lang));
        m.put("verifyNote", InvoiceLabel.VERIFY_NOTE.of(lang));
        // Color del lienzo (fuera del cuadro): lavanda en el email; el PDF lo sobreescribe a blanco.
        m.put("bodyBg", "#F4F1FB");
        m.put("ctaUrl", downloadUrl);
        m.put("ctaLabel", InvoiceLabel.CTA_DOWNLOAD.of(lang));
        m.put("footer", "NX036 Dropshipping · " + InvoiceLabel.RECEIPT_NOTE.of(lang));
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
        m.put("pdf", true); // documento de factura: sin saludo de email ni CTA
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
        // Mismo formateo locale-aware que el resto de la web ("62,15 €"), no "€62.15", para que la
        // factura sea consistente con el "Resumen de pago" del pedido y luzca profesional.
        return currencyRateService.formatDisplay(v, currency);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    /**
     * Genera un código QR del {@code content} y lo devuelve como data-URI PNG en base64 para incrustarlo
     * en el HTML/PDF de la factura. Si falla, devuelve "" (la plantilla simplemente no pinta el QR).
     */
    private String qrDataUri(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 240, 240, hints);
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", os);
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(os.toByteArray());
            }
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Nombre del país a partir del código ISO ("ES" → "España"), en el idioma del usuario. Si no se
     * reconoce el código, se devuelve tal cual para no perder el dato.
     */
    private static String countryName(String code, String locale) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String lang = locale == null ? "es" : locale.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        try {
            String name = new Locale("", code.trim().toUpperCase(Locale.ROOT))
                    .getDisplayCountry(Locale.forLanguageTag(lang));
            return name == null || name.isBlank() || name.equalsIgnoreCase(code) ? code : name;
        } catch (RuntimeException e) {
            return code;
        }
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
