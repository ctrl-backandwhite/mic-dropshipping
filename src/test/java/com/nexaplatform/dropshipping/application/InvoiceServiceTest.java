package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    TemplateEngine templateEngine;
    @Mock
    CurrencyRateService currencyRateService;

    InvoiceService service;

    /**
     * Se arma a mano en vez de con {@code @InjectMocks} porque {@link OrderAmounts} tiene que ser el
     * servicio REAL: la factura debe hacer la misma cuenta de línea que el cobro, y con un doble el test
     * dejaría de comprobar precisamente eso.
     */
    @BeforeEach
    void setUp() {
        // Conversión neutra por defecto (los casos que miden la divisa la sobreescriben con sus valores).
        lenient().when(currencyRateService.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0));
        lenient().when(currencyRateService.decimalsOf(anyString())).thenReturn(2);
        service = new InvoiceService(templateEngine, currencyRateService, mock(EuComplianceService.class),
                new OrderAmounts(currencyRateService), mock(PaymentJpaRepositoryAdapter.class),
                mock(ProductRepository.class), mock(ProductVariantRepository.class), mock(ObjectStorageService.class));
    }

    private static Order order(String currency, OrderItem... items) {
        return Order.builder().orderNumber("NX-100").currency(currency).shippingCents(500).taxCents(210)
                .items(List.of(items)).shippingCountry("ES").build();
    }

    private static OrderItem item(int unitCents, int qty, String title) {
        return OrderItem.builder().unitPriceCents(unitCents).quantity(qty).titleSnapshot(title).skuSnapshot("SKU")
                .build();
    }

    /** Línea con foto ya mirrorada en el storage (el caso normal del catálogo). */
    private static OrderItem itemWithImage(int unitCents, int qty, String title, String imageUrl) {
        return OrderItem.builder().unitPriceCents(unitCents).quantity(qty).titleSnapshot(title).skuSnapshot("SKU")
                .imageUrlSnapshot(imageUrl).build();
    }

    // El formateo de importes delega ahora en CurrencyRateService.formatDisplay (locale-aware).
    // Lo simulamos como "símbolo + valor" (€ para EUR, $ resto) para asertar los importes.
    private static String display(InvocationOnMock inv) {
        BigDecimal v = inv.getArgument(0);
        String c = inv.getArgument(1);
        return ("EUR".equals(c) ? "€" : "$") + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Test
    void model_usdSumsLinesAndUsesSpanishLabels() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);
        // 1000c=10.00 x2 = 20.00 ; 550c=5.50 x1 = 5.50 ; subtotal 25.50 ; +ship 5.00 +tax 2.10 = 32.60
        Map<String, Object> m = service.model(order("USD", item(1000, 2, "Camiseta"), item(550, 1, "Gorra")), "es",
                "http://dl");

        // País con nombre completo (ISO "ES" → "España").
        assertThat(m).containsEntry("subtotal", "$25.50").containsEntry("shipping", "$5.00")
                .containsEntry("tax", "$2.10").containsEntry("total", "$32.60")
                .containsEntry("title", "Pago confirmado").containsEntry("shipCountry", "España");
        assertThat((String) m.get("labelTax")).startsWith("IVA");
        // QR de verificación y URL con el nº de pedido.
        assertThat((String) m.get("qr")).startsWith("data:image/png;base64,");
        assertThat((String) m.get("verifyUrl")).contains("NX-100");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) m.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("lineTotal", "$20.00").containsEntry("qty", 2);
    }

    @Test
    void model_englishLabelsWhenLocaleNotEs() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);
        Map<String, Object> m = service.model(order("USD", item(1000, 1, "Tee")), "en", "http://dl");
        assertThat((String) m.get("labelTax")).startsWith("VAT");
        assertThat(m).containsEntry("title", "Payment confirmed").containsEntry("subject", "Invoice NX-100");
    }

    @Test
    void model_nonUsdCurrencyConvertsViaRateService() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);
        // Cada importe (línea, envío, impuesto) se convierte con usdTo; devolvemos valores deterministas.
        when(currencyRateService.usdTo(new BigDecimal("10.00"), "EUR")).thenReturn(new BigDecimal("9.00"));

        when(currencyRateService.usdTo(new BigDecimal("5.00"), "EUR")).thenReturn(new BigDecimal("4.50"));
        when(currencyRateService.usdTo(new BigDecimal("2.10"), "EUR")).thenReturn(new BigDecimal("1.89"));
        // El descuento de referido (v86) también se convierte: sin este stub el mock devuelve null y el
        // total revienta con NPE al restarlo. Pedido sin descuento → 0.
        when(currencyRateService.usdTo(new BigDecimal("0.00"), "EUR")).thenReturn(BigDecimal.ZERO);

        Map<String, Object> m = service.model(order("EUR", item(1000, 1, "Tee")), "es", "http://dl", "EUR");

        // subtotal 9.00 + envío 4.50 + impuesto 1.89 = 15.39
        assertThat(m).containsEntry("subtotal", "€9.00").containsEntry("total", "€15.39");
    }

    // ===== Fotos de producto en el EMAIL: adjuntas (cid:), no por URL remota =====
    // Con la URL del storage no se veían: en local apunta a localhost —que los servidores de Gmail no
    // pueden alcanzar cuando descargan la imagen por proxy— y Outlook/Apple Mail bloquean por defecto
    // las imágenes externas. Los iconos del correo ya usaban cid: y por eso sí se veían.

    @Test
    @SuppressWarnings("unchecked")
    void model_emailReferencesImagesByCidAndExposesTheirUrls() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);
        String url = "http://localhost:9100/product-images/media/4b/foto.jpg";

        Map<String, Object> m = service.model(order("USD", itemWithImage(1000, 1, "Tee", url)), "es", "http://dl");

        List<Map<String, Object>> items = (List<Map<String, Object>>) m.get("items");
        assertThat(items.get(0)).containsEntry("image", "cid:invitem-0");
        Map<String, String> inline = (Map<String, String>) m.get(InvoiceService.INLINE_IMAGES_KEY);
        assertThat(inline).containsEntry("invitem-0", url);
    }

    @Test
    @SuppressWarnings("unchecked")
    void model_emailGivesEachLineItsOwnCid() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);

        Map<String, Object> m = service.model(
                order("USD", itemWithImage(1000, 1, "Tee", "http://localhost:9100/product-images/a.jpg"),
                        itemWithImage(2000, 1, "Cap", "http://localhost:9100/product-images/b.jpg")),
                "es", "http://dl");

        List<Map<String, Object>> items = (List<Map<String, Object>>) m.get("items");
        assertThat(items.get(0)).containsEntry("image", "cid:invitem-0");
        assertThat(items.get(1)).containsEntry("image", "cid:invitem-1");
        Map<String, String> inline = (Map<String, String>) m.get(InvoiceService.INLINE_IMAGES_KEY);
        assertThat(inline).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void model_emailWithoutImageDeclaresNoInlineAttachment() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);

        Map<String, Object> m = service.model(order("USD", item(1000, 1, "Tee")), "es", "http://dl");

        List<Map<String, Object>> items = (List<Map<String, Object>>) m.get("items");
        assertThat(items.get(0)).containsEntry("image", "");
        assertThat((Map<String, String>) m.get(InvoiceService.INLINE_IMAGES_KEY)).isEmpty();
    }
}
