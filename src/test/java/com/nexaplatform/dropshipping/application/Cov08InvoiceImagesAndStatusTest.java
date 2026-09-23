package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fotos de las líneas de la factura, insignia de estado y pie legal.
 *
 * <p>La foto NO puede salir del snapshot congelado en el pedido: al re-espejar el catálogo la imagen se
 * vuelve a subir con otra clave y el snapshot apunta a un fichero que ya no existe, así que la factura
 * saldría con el hueco. Y para el PDF hay que incrustarla en base64, porque el renderizador corre en el
 * servidor y no puede descargar la URL del almacén.
 */
class Cov08InvoiceImagesAndStatusTest {

    private TemplateEngine templateEngine;
    private ProductVariantRepository variantRepository;
    private ObjectStorageService storage;
    private InvoiceService service;

    private static final UUID VARIANT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        templateEngine = mock(TemplateEngine.class);
        variantRepository = mock(ProductVariantRepository.class);
        storage = mock(ObjectStorageService.class);
        CurrencyRateService currency = mock(CurrencyRateService.class);
        when(currency.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.getArgument(0, BigDecimal.class).toPlainString());
        // Conversión 1:1 — esta clase mide las fotos de la factura, no los importes, pero la cuenta de
        // línea pasa igualmente por CurrencyRateService y sin stub devolvería null.
        when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        when(currency.decimalsOf(anyString())).thenReturn(2);
        PaymentJpaRepositoryAdapter payments = mock(PaymentJpaRepositoryAdapter.class);
        when(payments.findByOrderIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        service = new InvoiceService(templateEngine, currency, mock(EuComplianceService.class),
                new OrderAmounts(currency), payments, mock(ProductRepository.class), variantRepository, storage);
    }

    private static Order order(OrderStatus status, OrderItem... items) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrderNumber("NX-IMG-1");
        o.setStatus(status);
        o.setCurrency("USD");
        o.setShippingCountry("ES");
        o.setItems(List.of(items));
        return o;
    }

    private static OrderItem item(String snapshotUrl, UUID variantId) {
        OrderItem it = new OrderItem();
        it.setUnitPriceCents(1000);
        it.setQuantity(1);
        it.setTitleSnapshot("Camiseta");
        it.setImageUrlSnapshot(snapshotUrl);
        it.setVariantId(variantId);
        return it;
    }

    @SuppressWarnings("unchecked")
    private static String imagenDeLaPrimeraLinea(Map<String, Object> model) {
        return (String) ((List<Map<String, Object>>) model.get("items")).get(0).get("image");
    }

    private static ProductVariantEntity variante(String cdnUrl, String sourceUrl) {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setImageCdnUrl(cdnUrl);
        v.setImageSourceUrl(sourceUrl);
        return v;
    }

    // ─────────────────────── de dónde sale la foto ───────────────────────

    @Test
    void laFotoDeLaLineaSaleDeLaVarianteVivaYNoDelSnapshotDelPedido() {
        when(variantRepository.findById(VARIANT_ID))
                .thenReturn(Optional.of(variante("http://cdn/nueva.jpg", "http://origen/vieja.jpg")));

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/borrada.jpg", VARIANT_ID)), "es",
                null, "USD", false);

        Map<String, String> inline = inlineImages(m);
        assertThat(inline).containsEntry("invitem-0", "http://cdn/nueva.jpg");
    }

    @Test
    void siLaVarianteNoEstaEspejadaSeUsaSuUrlDeOrigen() {
        when(variantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variante(null, "http://origen/foto.jpg")));

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/borrada.jpg", VARIANT_ID)), "es",
                null, "USD", false);

        assertThat(inlineImages(m)).containsEntry("invitem-0", "http://origen/foto.jpg");
    }

    @Test
    void sinVarianteSeConservaLaFotoQueGuardoElPedido() {
        // Sin variante no hay imagen viva que resolver: el snapshot es lo que refleja lo comprado.
        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/comprado.jpg", null)), "es",
                null, "USD", false);

        assertThat(inlineImages(m)).containsEntry("invitem-0", "http://cdn/comprado.jpg");
    }

    @Test
    void unaVarianteQueYaNoExisteNoDejaLaLineaSinFoto() {
        when(variantRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/comprado.jpg", VARIANT_ID)),
                "es", null, "USD", false);

        assertThat(inlineImages(m)).containsEntry("invitem-0", "http://cdn/comprado.jpg");
    }

    // ─────────────────────── PDF: incrustada en base64 ───────────────────────

    @Test
    void enElPdfLaFotoViajaIncrustadaEnBase64() {
        // openhtmltopdf corre en el servidor: si se dejara la URL, el PDF saldría con la foto rota.
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        when(storage.bytesFromPublicUrl("http://cdn/foto.png")).thenReturn(png);

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/foto.png", null)), "es", null,
                "USD", true);

        assertThat(imagenDeLaPrimeraLinea(m)).startsWith("data:image/png;base64,");
        assertThat(inlineImages(m)).isEmpty();
    }

    @Test
    void elTipoDeImagenSeDeduceDeLosBytesYNoDeLaExtension() {
        // La extensión de la URL miente a menudo (el espejado renombra); un MIME equivocado deja la foto
        // sin pintar en el PDF.
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0};
        when(storage.bytesFromPublicUrl("http://cdn/foto.png")).thenReturn(jpeg);

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://cdn/foto.png", null)), "es", null,
                "USD", true);

        assertThat(imagenDeLaPrimeraLinea(m)).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void siLaFotoNoSePuedeDescargarSeDejaLaUrlEnLugarDeUnHueco() {
        when(storage.bytesFromPublicUrl("http://externo/foto.jpg")).thenReturn(new byte[0]);

        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("http://externo/foto.jpg", null)), "es",
                null, "USD", true);

        assertThat(imagenDeLaPrimeraLinea(m)).isEqualTo("http://externo/foto.jpg");
    }

    @Test
    void unaLineaSinFotoNoGeneraAdjuntoNiReferencia() {
        Map<String, Object> m = service.model(order(OrderStatus.PAID, item("   ", null)), "es", null, "USD", true);

        assertThat(imagenDeLaPrimeraLinea(m)).isEmpty();
        assertThat(inlineImages(m)).isEmpty();
    }

    // ─────────────────────── insignia de estado ───────────────────────

    @Test
    void unPedidoEntregadoSeSellaComoPagado() {
        Map<String, Object> m = service.model(order(OrderStatus.DELIVERED), "es", null);

        assertThat(m).containsEntry("statusPaid", true).containsEntry("statusLabel", "Pagada");
    }

    @Test
    void unPedidoQueAunNoSeHaCobradoSaleComoPendiente() {
        // El lado seguro: nunca decimos "pagada" sin serlo.
        Map<String, Object> m = service.model(order(OrderStatus.AWAITING_PAYMENT), "es", null);

        assertThat(m).containsEntry("statusPaid", false).containsEntry("statusLabel", "Pendiente");
    }

    @Test
    void unPedidoSinEstadoSeQuedaSinInsigniaEnLugarDeDecirPendiente() {
        // Afirmar que no está cobrado cuando no lo sabemos sería peor que no decir nada.
        Map<String, Object> m = service.model(order(null), "es", null);

        assertThat(m).containsEntry("statusPaid", false).containsEntry("statusLabel", "");
    }

    // ─────────────────────── pie legal y país ───────────────────────

    @Test
    void elPieLegalLlevaSiempreElNumeroDeFacturaAunqueNoHayaEmisorConfigurado() {
        Map<String, Object> m = service.model(order(OrderStatus.PAID), "es", null);

        assertThat((String) m.get("footerLegal")).isEqualTo("NX-IMG-1");
        assertThat(m).containsEntry("hasIssuer", false);
    }

    @Test
    void conEmisorConfiguradoElPieEncadenaRazonSocialCifCorreoYNumero() {
        ReflectionTestUtils.setField(service, "issuerLegalName", "NX036 Dropshipping S.L.");
        ReflectionTestUtils.setField(service, "issuerTaxId", "B12345678");
        ReflectionTestUtils.setField(service, "issuerEmail", "facturacion@nx.local");

        Map<String, Object> m = service.model(order(OrderStatus.PAID), "es", null);

        assertThat((String) m.get("footerLegal")).contains("NX036 Dropshipping S.L.").contains("B12345678")
                .contains("facturacion@nx.local").endsWith("NX-IMG-1");
        assertThat(m).containsEntry("hasIssuer", true);
    }

    @Test
    void unCodigoDePaisDesconocidoSeDejaTalCualParaNoPerderElDato() {
        // "QQ" no está en la tabla de países: la factura tiene que enseñar lo que trae el pedido en vez
        // de quedarse con el destino en blanco.
        Order o = order(OrderStatus.PAID);
        o.setShippingCountry("qq");

        assertThat(service.model(o, "es", null)).containsEntry("shipCountry", "qq");
    }

    @Test
    void elPaisSeTraduceAlIdiomaDeLaFactura() {
        Order o = order(OrderStatus.PAID);
        o.setShippingCountry("es");

        assertThat(service.model(o, "en", null)).containsEntry("shipCountry", "Spain");
    }

    @Test
    void unPedidoSinPaisDeEnvioNoRompeLaFactura() {
        Order o = order(OrderStatus.PAID);
        o.setShippingCountry(null);

        assertThat(service.model(o, "es", null)).containsEntry("shipCountry", "");
    }

    // ─────────────────────── render HTML ───────────────────────

    @Test
    void elHtmlDeLaFacturaSeRenderizaConLaPlantillaDeFacturaYLaMonedaDelPedido() {
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>factura</html>");

        String html = service.renderHtml(order(OrderStatus.PAID), "es", "http://descarga");

        assertThat(html).isEqualTo("<html>factura</html>");
        verify(templateEngine).process(eq("emails/invoice"), any(IContext.class));
    }

    @Test
    void unPedidoSinMonedaSeFacturaEnDolares() {
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");
        Order o = order(OrderStatus.PAID);
        o.setCurrency(null);

        assertThat(service.renderHtml(o, "es", null)).isEqualTo("<html/>");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> inlineImages(Map<String, Object> model) {
        return (Map<String, String>) model.get(InvoiceService.INLINE_IMAGES_KEY);
    }
}
