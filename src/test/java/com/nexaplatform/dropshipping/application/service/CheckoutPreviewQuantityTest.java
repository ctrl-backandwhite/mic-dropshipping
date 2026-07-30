package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tope de unidades por línea en la previsualización del checkout.
 *
 * <p>La cota existe porque el pipeline de importes es {@code int}: sin acotar, una cantidad enorme
 * desbordaba y la base del impuesto salía negativa —el preview enseñaba 0 de IVA y el cobro real no—.
 *
 * <p>Al pasar {@code Math.min(Math.max(1, qty), MAX)} a {@code Math.clamp} se colaron los argumentos en
 * el orden equivocado ({@code clamp(1, qty, MAX)}), y con eso una cantidad por encima del tope dejaba
 * {@code min > max}: en vez de acotarse, reventaba con {@code IllegalArgumentException} en mitad del
 * checkout. Estos tests fijan las tres fronteras.
 */
class CheckoutPreviewQuantityTest {

    private static final int MAX_LINE_QUANTITY = 100_000;

    private final ShippingQuoteService shipping = mock(ShippingQuoteService.class, RETURNS_DEEP_STUBS);
    private final CheckoutTotalsService totals = mock(CheckoutTotalsService.class, RETURNS_DEEP_STUBS);
    private final PricingService pricing = mock(PricingService.class, RETURNS_DEEP_STUBS);
    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AffiliateProgramService affiliate = mock(AffiliateProgramService.class);

    private final CheckoutPreviewService service =
            new CheckoutPreviewService(shipping, totals, pricing, currency, products, affiliate);

    private final UUID productId = UUID.randomUUID();

    private void stubUnitPriceOf(String retailUsd) {
        ProductEntity product = mock(ProductEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(pricing.priceFor(any(), any()).retailUsd()).thenReturn(new BigDecimal(retailUsd));
        lenient().when(currency.usdToDisplay(any(BigDecimal.class))).thenReturn(new BigDecimal(retailUsd));
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void unaCantidadPorEncimaDelTopeSeAcotaEnLugarDeReventar() {
        stubUnitPriceOf("1.00");

        CheckoutPreviewService.Preview preview = service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, MAX_LINE_QUANTITY + 5_000)), null);

        // 1,00 $ × 100.000 = 10.000.000 céntimos. Si no se acotara, serían 105.000 unidades.
        assertThat(preview.subtotalUsdCents()).isEqualTo(MAX_LINE_QUANTITY * 100);
    }

    @Test
    void unaCantidadDesmesuradaNoLanzaExcepcion() {
        stubUnitPriceOf("1.00");

        assertThatCode(() -> service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, Integer.MAX_VALUE)), null))
                .doesNotThrowAnyException();
    }

    @Test
    void unaCantidadCeroONegativaCuentaComoUnaUnidad() {
        stubUnitPriceOf("7.50");

        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 0)), null).subtotalUsdCents())
                .isEqualTo(750);
        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, -3)), null).subtotalUsdCents())
                .isEqualTo(750);
    }

    @Test
    void unaCantidadNormalNoSeToca() {
        stubUnitPriceOf("2.50");

        assertThat(service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 4)), null).subtotalUsdCents())
                .isEqualTo(1000);
    }
}
