package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.controller.ShippingQuoteController.QuoteResponse;
import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El desglose del checkout tiene que CUADRAR con el total, también cuando hay subvención.
 *
 * <p>Esta clase nace de un fallo real (21-ago-2026): al descontar la subvención del envío cobrado, la
 * línea «Envío» del resumen —que se calculaba restando el arancel al envío ya subvencionado— salió en
 * <b>−2,28 €</b>. El total era correcto, así que ninguna prueba de importes se enteró: lo que estaba mal
 * era el desglose, y el comprador veía un envío negativo y el descuento contado dos veces.
 *
 * <p>La regla que se fija aquí: <b>«Envío» es el porte del transportista SIN tocar</b>, el arancel va
 * aparte y la subvención va aparte, de modo que sumar lo que se ve dé exactamente el total que se cobra.
 */
class DesgloseConSubvencionTest {

    /** Porte del transportista: 9,87 € en la divisa del comprador. */
    private static final int PORTE = 987;
    /** Dos líneas de declaración a 3 EUR. */
    private static final int ARANCEL = 600;
    private static final int SUBVENCION = 1215;
    private static final int SUBTOTAL = 3621;
    private static final int IVA = 967;

    private final CheckoutPreviewService checkoutPreview = mock(CheckoutPreviewService.class);
    private final ShippingQuoteService shippingQuoteService = mock(ShippingQuoteService.class);
    private final CountryTaxService countryTaxService = mock(CountryTaxService.class);
    private final PricingService pricingService = mock(PricingService.class);
    private final CurrencyRateService currencyService = mock(CurrencyRateService.class);

    private final ShippingQuoteController controller = new ShippingQuoteController(checkoutPreview,
            shippingQuoteService, countryTaxService, pricingService, currencyService);

    @BeforeEach
    void sinConversionDeDivisa() {
        // La divisa del comprador es la canónica: así los números del desglose se leen en céntimos y el
        // cuadre se comprueba sobre las cifras, no sobre la conversión (que ya certifica otra clase).
        lenient().when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        lenient().when(currencyService.usdToDisplay(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.formatDisplay(any(), anyString()))
                .thenAnswer(i -> ((BigDecimal) i.getArgument(0)).setScale(2, RoundingMode.HALF_UP).toPlainString());
        lenient().when(checkoutPreview.compute(any(), any(), any(), any(), any(), any()))
                .thenReturn(previewConSubvencion());
    }

    @Test
    void elEnvioQueSeENSENAEsElPORTE_NoElYaSubvencionado() {
        // El fallo exacto: restar el arancel al envío subvencionado daba 9,87 + 6,00 − 12,15 − 6,00 =
        // −2,28. Un envío negativo en pantalla, y el descuento contado dos veces.
        QuoteResponse r = controller.quote(peticion(), null).getBody();

        assertThat(r).isNotNull();
        assertThat(r.shippingBaseFormatted()).isEqualTo("9.87");
        assertThat(new BigDecimal(r.shippingBaseFormatted())).isPositive();
    }

    @Test
    void loQueSeVEESumaExactamenteElTOTALQueSeCobra() {
        // La comprobación que faltaba: subtotal + envío + arancel − subvención + IVA = total. Si algún día
        // vuelve a desajustarse un componente, el cliente lo ve antes que nosotros.
        QuoteResponse r = controller.quote(peticion(), null).getBody();

        assertThat(r).isNotNull();
        int suma = SUBTOTAL + PORTE + ARANCEL - SUBVENCION + IVA;
        assertThat(r.totalUsdCents()).isEqualTo(suma);
    }

    @Test
    void elArancelSeEnsenaINTEGRO_NoRebajadoPorLaSubvencion() {
        // Lo que se declara y se liquida no baja: la subvención abarata lo que paga el cliente, y va como
        // línea aparte precisamente para que nadie lea el arancel como si fuera menor.
        QuoteResponse r = controller.quote(peticion(), null).getBody();

        assertThat(r).isNotNull();
        assertThat(r.customsHandlingUsdCents()).isEqualTo(ARANCEL);
        assertThat(r.customsHandlingFormatted()).isEqualTo("6.00");
    }

    private static ShippingQuoteController.QuoteRequest peticion() {
        return new ShippingQuoteController.QuoteRequest("ES", null, List.of(), null, null);
    }

    private static CheckoutPreviewService.Preview previewConSubvencion() {
        ShippingQuote quote = new ShippingQuote(true, "ES", PORTE, "Standard", "Standard", 5, 8, "EU", List.of());
        CustomsValuation customs = new CustomsValuation("ES", TaxMode.DDP, SUBTOTAL, false, null, ARANCEL, false,
                "150 EUR", false);
        // El envío COBRADO ya lleva la subvención descontada; el porte base, no.
        int cobrado = PORTE + ARANCEL - SUBVENCION;
        // La bolsa cubre el porte entero y parte del arancel, que es como se reparte de verdad.
        CheckoutTotalsService.CheckoutTotals totals = new CheckoutTotalsService.CheckoutTotals(PORTE, ARANCEL, cobrado,
                IVA, 2100, customs, PORTE, SUBVENCION - PORTE);
        return new CheckoutPreviewService.Preview(quote, SUBTOTAL, 0, cobrado, IVA, 2100,
                BigDecimal.valueOf(SUBTOTAL, 2), BigDecimal.ZERO, BigDecimal.valueOf(cobrado, 2),
                BigDecimal.valueOf(IVA, 2), BigDecimal.valueOf(SUBTOTAL + cobrado + IVA, 2), totals, null, null, null,
                List.of(), null);
    }
}
