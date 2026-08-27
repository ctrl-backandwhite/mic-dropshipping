package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La cotización publica las formas de envío entre las que el cliente puede elegir, y recotiza con la
 * que elija.
 *
 * <p>Cada opción viaja con su importe YA FORMATEADO en la divisa del comprador, como el resto de los
 * precios del checkout: el navegador no convierte ni redondea nada. Lo que NO viaja es el nombre del
 * canal del transportista —«云途全球服装专线挂号»—: el código es un identificador interno para poder
 * emitir la guía por el mismo canal que se cotizó, y el texto que ve el cliente lo pone el front.
 */
class ShippingQuoteOptionsControllerTest {

    private static final List<ShippingOption> OPCIONES = List.of(
            new ShippingOption("FZZXR", "Apparel line", 785, 5, 8, "YUNEXPRESS"),
            new ShippingOption("THPHR", "Global line", 821, 6, 10, "YUNEXPRESS"));

    private final CheckoutPreviewService checkoutPreview = mock(CheckoutPreviewService.class);
    private final ShippingQuoteService shippingQuoteService = mock(ShippingQuoteService.class);
    private final CountryTaxService countryTaxService = mock(CountryTaxService.class);
    private final PricingService pricingService = mock(PricingService.class);
    private final CurrencyRateService currencyService = mock(CurrencyRateService.class);

    private final ShippingQuoteController controller = new ShippingQuoteController(checkoutPreview,
            shippingQuoteService, countryTaxService, pricingService, currencyService);

    /** Vista previa mínima con las dos opciones cotizadas y {@code elegida} como la aplicada. */
    private static CheckoutPreviewService.Preview preview(ShippingOption elegida) {
        ShippingQuote quote = new ShippingQuote(true, "ES", 785, "Standard Shipping", "Standard Shipping",
                5, 8, "EU", OPCIONES);
        CustomsValuation customs = new CustomsValuation("ES", TaxMode.DDP, 1000, false, null, 0, false,
                "150 EUR", true);
        CheckoutTotalsService.CheckoutTotals totals = new CheckoutTotalsService.CheckoutTotals(785, 0, 785,
                375, 2100, customs);
        return new CheckoutPreviewService.Preview(quote, 1000, 0, 785, 375, 2100, new BigDecimal("10.00"),
                BigDecimal.ZERO, new BigDecimal("7.85"), new BigDecimal("3.75"), new BigDecimal("21.60"),
                totals, null, null, null, List.of(), elegida);
    }

    @BeforeEach
    void divisaEuro() {
        lenient().when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        lenient().when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).toPlainString() + " €");
        lenient().when(checkoutPreview.compute(anyString(), nullable(String.class), any(), nullable(java.util.UUID.class),
                nullable(String.class), nullable(String.class))).thenReturn(preview(OPCIONES.getFirst()));
    }

    private ShippingQuoteController.QuoteResponse cotizar(String shippingOptionCode) {
        return controller.quote(new ShippingQuoteController.QuoteRequest("ES", null, List.of(), null,
                shippingOptionCode), null).getBody();
    }

    @Test
    @DisplayName("cada opción dice qué empresa lleva el paquete, con un nombre presentable")
    void cadaOpcionDiceQuienLoLleva() {
        // Decisión del dueño (19-ago-2026): el cliente ve con quién viaja su pedido. Se enseña el nombre
        // de la EMPRESA, no el código del canal («FZZXR» o «1868922929754472449» no le dicen nada a
        // nadie), y lo compone el backend: si lo maquillara el navegador, cada pantalla acabaría
        // llamándolo de una manera.
        ShippingQuoteController.QuoteResponse res = cotizar(null);

        assertThat(res.options().getFirst().carrierName()).isEqualTo("YunExpress");
    }

    @Test
    @DisplayName("un transportista sin nombre conocido no deja la opción sin rótulo")
    void transportistaDesconocido() {
        // Si mañana se añade un tercero y nadie se acuerda de darle nombre, la opción tiene que seguir
        // siendo comprensible en vez de enseñar un hueco o un identificador interno.
        ShippingQuote quote = new ShippingQuote(true, "ES", 785, "Standard Shipping", "Standard Shipping",
                5, 8, "EU", List.of(new ShippingOption("X", "Otra", 785, 5, 8, "TRANSPORTISTA_NUEVO")));
        CustomsValuation customs = new CustomsValuation("ES", TaxMode.DDP, 1000, false, null, 0, false,
                "150 EUR", true);
        CheckoutTotalsService.CheckoutTotals totals = new CheckoutTotalsService.CheckoutTotals(785, 0, 785,
                375, 2100, customs);
        lenient().when(checkoutPreview.compute(anyString(), nullable(String.class), any(),
                nullable(java.util.UUID.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new CheckoutPreviewService.Preview(quote, 1000, 0, 785, 375, 2100,
                        new BigDecimal("10.00"), BigDecimal.ZERO, new BigDecimal("7.85"),
                        new BigDecimal("3.75"), new BigDecimal("21.60"), totals, null, null, null,
                        List.of(), null));

        ShippingQuoteController.QuoteResponse res = cotizar(null);

        assertThat(res.options().getFirst().carrierName()).isNotBlank();
    }

    @Test
    @DisplayName("la cotización publica el TOTAL en céntimos de dólar, no solo formateado")
    void publicaElTotalEnCentimosDeDolar() {
        // El checkout tiene que decidir si el monedero llega para pagar, y el saldo vive en céntimos de
        // DÓLAR. Con solo el total formateado («21,60 €») la única salida sería que el navegador
        // convirtiera o interpretara ese texto, que es justo lo que la norma de precios prohíbe: el
        // front pinta, no calcula. Publicando el canónico, comparar es restar dos números de la misma
        // unidad, sin que nadie los toque por el camino.
        ShippingQuoteController.QuoteResponse res = cotizar(null);

        // 1.000 de producto + 785 de envío + 375 de impuesto.
        assertThat(res.totalUsdCents()).isEqualTo(2160);
    }

    @Test
    @DisplayName("el total en céntimos incluye envío e impuesto, no es el subtotal")
    void elTotalNoEsElSubtotal() {
        ShippingQuoteController.QuoteResponse res = cotizar(null);

        assertThat(res.totalUsdCents())
                .as("comparar el saldo contra el SUBTOTAL deja pasar pedidos que el cobro luego rechaza: "
                        + "el cliente confirma, se crea el pedido y el cargo falla")
                .isGreaterThan(res.subtotalUsdCents());
    }

    @Test
    @DisplayName("cada opción viaja con su precio ya formateado y su plazo, sin el nombre del canal")
    void lasOpcionesSePublicanFormateadas() {
        ShippingQuoteController.QuoteResponse res = cotizar(null);

        assertThat(res.options()).hasSize(2);
        assertThat(res.options().getFirst().code()).isEqualTo("FZZXR");
        assertThat(res.options().getFirst().amountFormatted()).isEqualTo("7.85 €");
        assertThat(res.options().getFirst().etaMinDays()).isEqualTo(5);
        assertThat(res.options().getFirst().etaMaxDays()).isEqualTo(8);
    }

    @Test
    @DisplayName("el canal elegido llega a la vista previa para que el total se calcule con él")
    void elCanalElegidoSePasaAlCalculo() {
        cotizar("THPHR");

        ArgumentCaptor<String> canal = ArgumentCaptor.forClass(String.class);
        verify(checkoutPreview).compute(anyString(), nullable(String.class), any(),
                nullable(java.util.UUID.class), nullable(String.class), canal.capture());
        assertThat(canal.getValue()).isEqualTo("THPHR");
    }

    @Test
    @DisplayName("la respuesta dice qué opción se ha aplicado, que no siempre es la pedida")
    void laRespuestaEcoaLaOpcionAplicada() {
        // El caso que importa: se pide un canal que ya no cotiza y el servidor cobra la más barata. Sin
        // el eco, el checkout dejaría marcada la que el cliente eligió y el precio sería el de otra.
        when(checkoutPreview.compute(anyString(), nullable(String.class), any(), nullable(java.util.UUID.class),
                nullable(String.class), nullable(String.class))).thenReturn(preview(OPCIONES.getFirst()));

        assertThat(cotizar("CNDWA").selectedShippingOptionCode()).isEqualTo("FZZXR");
    }

    @Test
    @DisplayName("sin canales cotizados no hay opción aplicada que anunciar")
    void sinOpcionesElEcoVaVacio() {
        when(checkoutPreview.compute(anyString(), nullable(String.class), any(), nullable(java.util.UUID.class),
                nullable(String.class), nullable(String.class))).thenReturn(preview(null));

        assertThat(cotizar(null).selectedShippingOptionCode()).isNull();
    }
}
