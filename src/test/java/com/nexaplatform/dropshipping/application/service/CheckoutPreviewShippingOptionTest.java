package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * El resumen del checkout se cotiza con la forma de envío que el cliente ha elegido.
 *
 * <p>Sin esto, el selector enseñaría una opción más cara mientras el total sigue calculado con la más
 * barata: el cliente elige «exprés», ve el precio de «económico» y descubre la diferencia en el cargo.
 * El importe del envío entra además en la base del impuesto, así que la cuenta entera depende de qué
 * canal se aplique — por eso se resuelve aquí, en el servidor, y no restando en el navegador.
 *
 * <p>El código llega del navegador y se revalida contra la cotización recién hecha, igual que al cobrar
 * ({@link ShippingOptionResolver}): un canal que no cotiza no se cobra, se cae a la más barata.
 */
class CheckoutPreviewShippingOptionTest {

    /** Cotización real de España: la línea de ropa es más barata y más rápida que la generalista. */
    private static final List<ShippingOption> OPCIONES = List.of(
            new ShippingOption("FZZXR", "Apparel line", 785, 5, 8),
            new ShippingOption("THPHR", "Global line", 821, 6, 10));

    private final ShippingQuoteService shipping = mock(ShippingQuoteService.class);
    private final CheckoutTotalsService totals = mock(CheckoutTotalsService.class, RETURNS_DEEP_STUBS);
    private final PricingService pricing = mock(PricingService.class, RETURNS_DEEP_STUBS);
    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AffiliateProgramService affiliate = mock(AffiliateProgramService.class);
    private final PromotionService promociones = mock(PromotionService.class);
    private final CustomsDutyLinesService dutyLines = new CustomsDutyLinesService();
    private final OrderAmounts orderAmounts = new OrderAmounts(currency);

    private final CheckoutPreviewService service = new CheckoutPreviewService(shipping, totals, pricing,
            currency, products, dutyLines, affiliate, promociones, orderAmounts);

    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void carritoDeUnProductoConDosCanalesCotizados() {
        ProductEntity product = mock(ProductEntity.class, RETURNS_DEEP_STUBS);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(pricing.priceFor(any(), any()).retailUsd()).thenReturn(new BigDecimal("10.00"));
        lenient().when(pricing.priceFor(any(), any()).displayAmount()).thenReturn(new BigDecimal("10.00"));
        lenient().when(currency.usdToDisplay(any(BigDecimal.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenReturn(BigDecimal.ZERO);
        lenient().when(currency.decimalsOf(anyString())).thenReturn(2);
        lenient().when(affiliate.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        lenient().when(shipping.quote(anyString(), any())).thenReturn(new ShippingQuote(true, "ES", 785,
                "Standard Shipping", "Standard Shipping", 5, 8, "EU", OPCIONES));
    }

    private CheckoutPreviewService.Preview previewCon(String shippingOptionCode) {
        return service.compute("ES", null,
                List.of(new CheckoutPreviewService.Line(productId, null, 1)), null, null, shippingOptionCode);
    }

    /** La tarifa que el desglose ha usado como base del envío (y, con ella, de la base del impuesto). */
    private int tarifaUsada() {
        ArgumentCaptor<Integer> envio = ArgumentCaptor.forClass(Integer.class);
        verify(totals).compute(anyString(), any(), anyInt(), envio.capture(), any());
        return envio.getValue();
    }

    @Test
    @DisplayName("elegir un canal más caro cotiza el resumen con SU tarifa, no con la más barata")
    void laOpcionElegidaMandaEnElResumen() {
        CheckoutPreviewService.Preview preview = previewCon("THPHR");

        assertThat(tarifaUsada()).isEqualTo(821);
        assertThat(preview.shippingOption().code()).isEqualTo("THPHR");
    }

    @Test
    @DisplayName("sin elegir nada se cotiza la más barata")
    void sinEleccionSeCotizaLaMasBarata() {
        CheckoutPreviewService.Preview preview = previewCon(null);

        assertThat(tarifaUsada()).isEqualTo(785);
        assertThat(preview.shippingOption().code()).isEqualTo("FZZXR");
    }

    @Test
    @DisplayName("un canal que no cotiza no rebaja el envío: se cae a la más barata")
    void unCanalInventadoNoAbarataElResumen() {
        // El intento evidente: mandar un postal barato para que el checkout enseñe menos envío.
        CheckoutPreviewService.Preview preview = previewCon("CNDWA");

        assertThat(tarifaUsada()).isEqualTo(785);
        assertThat(preview.shippingOption().code())
                .as("el checkout tiene que poder marcar la que de verdad se está cobrando")
                .isEqualTo("FZZXR");
    }

    @Test
    @DisplayName("sin canales cotizados (tarifa de tabla de zonas) el envío sigue siendo el de la cotización")
    void sinOpcionesSeUsaLaTarifaDeLaCotizacion() {
        lenient().when(shipping.quote(anyString(), any())).thenReturn(new ShippingQuote(true, "ES", 750,
                "Standard Shipping", "Standard Shipping", 5, 12, "EU"));

        CheckoutPreviewService.Preview preview = previewCon("THPHR");

        assertThat(tarifaUsada()).isEqualTo(750);
        assertThat(preview.shippingOption()).isNull();
    }

    @Test
    @DisplayName("un país sin cobertura no cobra envío aunque se pida un canal")
    void unPaisSinCoberturaNoCobraEnvio() {
        lenient().when(shipping.quote(anyString(), any())).thenReturn(new ShippingQuote(false, "CU", 0,
                null, null, 0, 0, null));

        previewCon("THPHR");

        verify(totals).compute(anyString(), any(), anyInt(), eq(0), any());
    }
}
