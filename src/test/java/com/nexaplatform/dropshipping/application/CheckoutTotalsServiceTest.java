package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService.CheckoutTotals;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Desglose del checkout: orden del cálculo (impuesto antes del recargo) y composición del total.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutTotalsServiceTest {

    @Mock
    CountryTaxService taxService;
    @Mock
    CustomsValuationService customsValuationService;
    @InjectMocks
    CheckoutTotalsService service;

    private void givenTax(int rateBps, int taxCents) {
        when(taxService.rateBpsFor(any(), any())).thenReturn(rateBps);
        when(taxService.taxCentsFor(any(), any(), anyInt())).thenReturn(taxCents);
    }

    private void givenCustoms(int handlingCents, boolean exceeded, boolean blocked) {
        when(customsValuationService.valuate(any(), anyInt(), anyInt(), anyList())).thenReturn(new CustomsValuation("ES",
                TaxMode.DDP, 0, exceeded, OverThresholdPolicy.SURCHARGE, handlingCents, blocked, "", false));
    }

    @Test
    void el_impuesto_se_calcula_sobre_subtotal_descontado_mas_envio_base() {
        givenTax(2100, 25_20);
        givenCustoms(0, false, false);

        service.compute("ES", null, 100_00, 20_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)));

        // Base imponible = 100,00 + 20,00 = 120,00 (el recargo de despacho NO entra en la base)
        verify(taxService).taxCentsFor("ES", null, 120_00);
    }

    @Test
    void el_recargo_de_despacho_se_suma_al_envio_no_a_la_base_imponible() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);

        CheckoutTotals t = service.compute("ES", null, 100_00, 20_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)));

        assertThat(t.shippingBaseCents()).isEqualTo(20_00);
        assertThat(t.customsHandlingCents()).isEqualTo(3_00);
        assertThat(t.shippingCents()).isEqualTo(23_00);
        // El impuesto sigue siendo el de la base sin recargo
        assertThat(t.taxCents()).isEqualTo(25_20);
    }

    @Test
    void el_total_suma_subtotal_descontado_envio_con_recargo_e_impuesto() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);

        CheckoutTotals t = service.compute("ES", null, 100_00, 20_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)));

        // 100,00 + (20,00 + 3,00) + 25,20 = 148,20
        assertThat(t.totalCents(100_00)).isEqualTo(148_20);
    }

    @Test
    void propaga_el_umbral_superado_y_el_bloqueo_del_pais() {
        givenTax(0, 0);
        givenCustoms(15_00, true, true);

        CheckoutTotals t = service.compute("BR", null, 300_00, 10_00, List.of(new DutyParcel(Math.max(0, 300_00), 1)));

        assertThat(t.customs().deMinimisExceeded()).isTrue();
        assertThat(t.blocked()).isTrue();
        assertThat(t.shippingCents()).isEqualTo(25_00);
    }

    @Test
    void un_envio_negativo_o_un_subtotal_negativo_se_normalizan_a_cero() {
        givenTax(0, 0);
        givenCustoms(0, false, false);

        CheckoutTotals t = service.compute("ES", null, -10_00, -5_00, List.of(new DutyParcel(Math.max(0, -10_00), 1)));

        assertThat(t.shippingBaseCents()).isZero();
        assertThat(t.shippingCents()).isZero();
        verify(taxService).taxCentsFor("ES", null, 0);
    }

    @Test
    void el_impuesto_por_region_se_resuelve_con_el_estado_recibido() {
        givenTax(875, 8_75);
        givenCustoms(0, false, false);

        CheckoutTotals t = service.compute("US", "CA", 100_00, 0, List.of(new DutyParcel(Math.max(0, 100_00), 1)));

        verify(taxService).taxCentsFor("US", "CA", 100_00);
        assertThat(t.taxRateBps()).isEqualTo(875);
    }

    @Test
    void laSubvencionBajaLoQueSECOBRASinTocarLoQueCUESTA() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);
        // La bolsa se descuenta del envío y el arancel que paga el cliente. Lo que NO se mueve es el porte
        // base —es lo que nos cuesta el transportista— ni el derecho declarado, que se liquida íntegro.
        CheckoutTotals t = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 10_00);

        assertThat(t.shippingBaseCents()).as("el porte base NO se toca: es lo que cuesta de verdad")
                .isEqualTo(20_00);
        assertThat(t.customsHandlingCents()).as("el derecho declarado tampoco").isEqualTo(3_00);
        assertThat(t.shippingSubsidyCents()).isEqualTo(10_00);
        assertThat(t.shippingCents()).isEqualTo(20_00 + 3_00 - 10_00);
        assertThat(t.freeShipping()).isFalse();
    }

    @Test
    void elARANCELQueSEDECLARANoBajaConLaSubvencion() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);
        // La invariante que no se puede romper: la bolsa abarata lo que PAGA el cliente, nunca lo que se
        // declara ni lo que se liquida. Declarar menos sería infradeclarar ante 27 aduanas, y de eso
        // responde el declarante.
        CheckoutTotals conBolsa = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 50_00);
        CheckoutTotals sinBolsa = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 0);

        assertThat(conBolsa.customsHandlingCents()).isEqualTo(sinBolsa.customsHandlingCents());
    }

    @Test
    void laSubvencionNoDejaImportesNEGATIVOS() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);
        // Si la bolsa da para más que el envío y el arancel, el sobrante se queda como ganancia: no se
        // devuelve en metálico ni se resta de la mercancía.
        CheckoutTotals t = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 999_00);

        assertThat(t.shippingCents()).isZero();
        assertThat(t.shippingSubsidyCents())
                .as("solo se apunta lo que de verdad se ha gastado de la bolsa")
                .isEqualTo(20_00 + t.customsHandlingCents());
    }

    @Test
    void elIMPUESTONoBajaConLaSubvencion() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);
        // El IVA lo fija la base imponible real —mercancía más porte—, no lo que acabemos regalando.
        // Calcularlo sobre el importe subvencionado sería liquidar de menos.
        CheckoutTotals conBolsa = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 20_00);
        CheckoutTotals sinBolsa = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 0);

        assertThat(conBolsa.taxCents()).isEqualTo(sinBolsa.taxCents());
    }

    @Test
    void sinSubvencionElResultadoEsElDeSIEMPRE() {
        givenTax(2100, 25_20);
        givenCustoms(3_00, false, false);
        CheckoutTotals conCero = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)), 0);
        CheckoutTotals sinParametro = service.compute("ES", null, 100_00, 20_00,
                List.of(new DutyParcel(100_00, 1)));

        assertThat(conCero.shippingCents()).isEqualTo(sinParametro.shippingCents());
        assertThat(conCero.shippingSubsidyCents()).isZero();
    }
}
