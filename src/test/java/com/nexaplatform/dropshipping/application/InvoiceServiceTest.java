package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    TemplateEngine templateEngine;
    @Mock
    CurrencyRateService currencyRateService;
    @InjectMocks
    InvoiceService service;

    private static Order order(String currency, OrderItem... items) {
        return Order.builder().orderNumber("NX-100").currency(currency).shippingCents(500).taxCents(210)
                .items(List.of(items)).shippingCountry("ES").build();
    }

    private static OrderItem item(int unitCents, int qty, String title) {
        return OrderItem.builder().unitPriceCents(unitCents).quantity(qty).titleSnapshot(title).skuSnapshot("SKU")
                .build();
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
        Map<String, Object> m = service.model(order("USD", item(1000, 2, "Camiseta"), item(550, 1, "Gorra")),
                "es", "http://dl");

        assertThat(m.get("subtotal")).isEqualTo("$25.50");
        assertThat(m.get("shipping")).isEqualTo("$5.00");
        assertThat(m.get("tax")).isEqualTo("$2.10");
        assertThat(m.get("total")).isEqualTo("$32.60");
        assertThat((String) m.get("labelTax")).startsWith("IVA");
        assertThat(m.get("title")).isEqualTo("Pago confirmado");
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
        assertThat(m.get("title")).isEqualTo("Payment confirmed");
        assertThat(m.get("subject")).isEqualTo("Invoice NX-100");
    }

    @Test
    void model_nonUsdCurrencyConvertsViaRateService() {
        when(currencyRateService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(InvoiceServiceTest::display);
        // Cada importe (línea, envío, impuesto) se convierte con usdTo; devolvemos valores deterministas.
        when(currencyRateService.usdTo(eq(new BigDecimal("10.00")), eq("EUR"))).thenReturn(new BigDecimal("9.00"));
        when(currencyRateService.usdTo(eq(new BigDecimal("5.00")), eq("EUR"))).thenReturn(new BigDecimal("4.50"));
        when(currencyRateService.usdTo(eq(new BigDecimal("2.10")), eq("EUR"))).thenReturn(new BigDecimal("1.89"));

        Map<String, Object> m = service.model(order("EUR", item(1000, 1, "Tee")), "es", "http://dl", "EUR");

        // subtotal 9.00 + envío 4.50 + impuesto 1.89 = 15.39
        assertThat(m.get("subtotal")).isEqualTo("€9.00");
        assertThat(m.get("total")).isEqualTo("€15.39");
    }
}
