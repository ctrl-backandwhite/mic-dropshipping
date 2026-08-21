package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService.CheckoutTotals;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.ShippingSubsidyService;
import com.nexaplatform.dropshipping.application.service.ShippingSubsidyService.Linea;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Las cuentas de la subvención del envío, al céntimo y al punto porcentual.
 *
 * <p>Esta clase existe por historia: en este proyecto los descuadres han venido siempre de los decimales
 * —multiplicar un unitario ya redondeado por la cantidad cobró un 1,45 % de más durante días, y componer
 * el precio en euros en vez de en dólares anunciaba 14,79 € para cobrar 14,78 €—. Una subvención es una
 * resta sobre dinero que ya se enseñó, así que un céntimo de deriva aquí es un céntimo que el cliente ve
 * y no le cuadra.
 *
 * <p>Todo va en <b>enteros de céntimo</b>: la única conversión con decimales es el suelo de 5 EUR a
 * dólares, y es la que se ata primero.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubvencionEnvioRedondeoTest {

    private static final UUID PRODUCTO = UUID.randomUUID();
    /** La tasa real sembrada el 21-ago-2026: 1 EUR = 0,855249 USD... al revés, 1 USD = 0,855249 EUR. */
    private static final BigDecimal TASA_EUR = new BigDecimal("0.855249");

    @Mock
    CustomsValuationService customsValuation;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    CountryTaxService taxService;

    ShippingSubsidyService subsidy;
    CheckoutTotalsService totals;

    @BeforeEach
    void montaje() {
        subsidy = new ShippingSubsidyService(customsValuation, currencyService);
        ReflectionTestUtils.setField(subsidy, "sueloDeGananciaEur", new BigDecimal("5"));
        totals = new CheckoutTotalsService(taxService, customsValuation);
        when(customsValuation.perArticleFeeUsdCents(anyString())).thenReturn(300);
        // Conversión EUR→USD con la tasa real, con toda la precisión y redondeando al final.
        when(currencyService.toUsd(any(), anyString())).thenAnswer(inv ->
                ((BigDecimal) inv.getArgument(0)).divide(TASA_EUR, 6, RoundingMode.HALF_UP));
        when(taxService.rateBpsFor(any(), any())).thenReturn(2100);
        when(taxService.taxCentsFor(any(), any(), anyInt())).thenReturn(0);
        when(customsValuation.valuate(any(), anyInt(), anyInt(), anyList())).thenReturn(new CustomsValuation(
                "ES", TaxMode.DDP, 0, false, OverThresholdPolicy.SURCHARGE, 300, false, "", false));
    }

    @Nested
    @DisplayName("El suelo de 5 EUR convertido a dólares")
    class Suelo {

        /** 5 / 0,855249 = 5,8462... USD → 585 céntimos. */
        private static final int SUELO_USD = 585;

        @Test
        void unaGananciaEXACTAMENTEEnElSueloNoAportaNada() {
            // El borde exacto es del negocio: «superan los 5 euros» excluye los 5 euros clavados.
            assertThat(subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, SUELO_USD, 0)), "ES")).isZero();
        }

        @Test
        void unCentimoPorEncimaDelSueloAportaExactamenteUnCentimo() {
            // Sin esto, un redondeo perezoso del suelo se comería o regalaría céntimos en el borde.
            assertThat(subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, SUELO_USD + 1, 0)), "ES"))
                    .isEqualTo(1);
        }

        @Test
        void unCentimoPorDEBAJONoAportaNada() {
            assertThat(subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, SUELO_USD - 1, 0)), "ES")).isZero();
        }
    }

    @Nested
    @DisplayName("La cuenta de las unidades no pierde ni gana céntimos")
    class Unidades {

        @Test
        void laGananciaSeMultiplicaANTESDeRestarElSuelo() {
            // Tres unidades de 3,00 USD son 9,00 de ganancia, y de ahí sale el suelo UNA vez: 900 − 585.
            // Restar el suelo por unidad daría cero y se perdería toda la subvención.
            assertThat(subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 3, 300, 0)), "ES"))
                    .isEqualTo(900 - 585);
        }

        @Test
        void unaCantidadGRANDENoDesbordaNiPierdePrecision() {
            // El carrito acota la cantidad, pero este cálculo no puede ser quien reviente.
            int esperado = 10_000 * 250 - 585 + (10_000 - 1) * 220;
            assertThat(subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 10_000, 250, 220)), "ES"))
                    .isEqualTo(esperado);
        }

        @Test
        void variasLineasDelMismoProductoSumanUnidadesAntesDeContarPortes() {
            // 2 + 3 unidades del mismo producto en variantes distintas son CINCO unidades y CUATRO portes
            // repetidos, no dos lotes de portes por separado (que darían 1 + 2 = 3).
            List<Linea> carrito = List.of(new Linea(PRODUCTO, 2, 0, 220), new Linea(PRODUCTO, 3, 0, 220));

            assertThat(subsidy.subsidyUsdCents(carrito, "ES")).isEqualTo(4 * 220);
        }
    }

    @Nested
    @DisplayName("El porcentaje que se le enseña al cliente")
    class Porcentaje {

        private CheckoutTotals conBolsa(int porte, int bolsa) {
            return totals.compute("ES", null, 100_00, porte, List.of(new DutyParcel(100_00, 1)), bolsa);
        }

        @Test
        void cubrirLaMitadEsElCincuentaPorCiento() {
            // Porte 17,00 + arancel 3,00 = 20,00; bolsa 10,00.
            assertThat(conBolsa(17_00, 10_00).subsidyPercent()).isEqualTo(50);
        }

        @Test
        void unTercioRedondeaAlEnteroMasCercano() {
            // 10,00 de 30,00 (27,00 de porte + 3,00 de arancel) = 33,33 % → 33.
            assertThat(conBolsa(27_00, 10_00).subsidyPercent()).isEqualTo(33);
            // 20,00 de 30,00 = 66,67 % → 67, no 66: se redondea, no se trunca.
            assertThat(conBolsa(27_00, 20_00).subsidyPercent()).isEqualTo(67);
        }

        @Test
        void cubrirloTodoEsCienYNoCientoVeinte() {
            // La bolsa puede dar para más de lo que hay que pagar; el porcentaje se queda en 100 porque
            // solo se apunta lo gastado. Un «120 %» en pantalla no significaría nada.
            CheckoutTotals t = conBolsa(17_00, 99_00);

            assertThat(t.subsidyPercent()).isEqualTo(100);
            assertThat(t.freeShipping()).isTrue();
            assertThat(t.shippingCents()).isZero();
        }

        @Test
        void sinBolsaElPorcentajeEsCeroYNoSeDivideEntreCero() {
            assertThat(conBolsa(17_00, 0).subsidyPercent()).isZero();
            assertThat(conBolsa(17_00, 0).freeShipping()).isFalse();
            // Y sin envío ni arancel que cobrar tampoco revienta.
            assertThat(totals.compute("ES", null, 0, 0, List.of(), 0).subsidyPercent()).isZero();
        }
    }

    @Nested
    @DisplayName("Lo que se cobra sigue cuadrando con lo que se enseña")
    class Cuadre {

        @Test
        void elTotalEsElSubtotalMasElEnvioYASubvencionadoMasElImpuesto() {
            // La comprobación que impide el descuadre clásico: el total tiene que salir de sumar
            // exactamente los componentes que el cliente ve en el desglose, no de otra cuenta paralela.
            when(taxService.taxCentsFor(any(), any(), anyInt())).thenReturn(21_00);
            CheckoutTotals t = totals.compute("ES", null, 100_00, 17_00,
                    List.of(new DutyParcel(100_00, 1)), 8_00);

            assertThat(t.shippingCents()).isEqualTo(17_00 + 3_00 - 8_00);
            assertThat(t.totalCents(100_00)).isEqualTo(100_00 + t.shippingCents() + 21_00);
        }

        @Test
        void loAplicadoNuncaSuperaLoQueHabiaQueCobrar() {
            CheckoutTotals t = totals.compute("ES", null, 100_00, 17_00,
                    List.of(new DutyParcel(100_00, 1)), 500_00);

            assertThat(t.subsidyCents()).isEqualTo(17_00 + 3_00);
            assertThat(t.shippingCents()).isZero();
        }
    }

    @Nested
    @DisplayName("El escenario del dueño, con sus números")
    class EscenarioDelDueno {

        /**
         * Carrito de 120 €, envío 30 €, arancel 24 €, ganancia 70 €.
         *
         * <p>De los 70 se apartan 5 para el negocio: quedan 65 de bolsa. Cubren el envío entero (30) y
         * el arancel entero (24) = 54, y los 11 que sobran <b>no se gastan</b>: vuelven a la ganancia.
         * El negocio se lleva 5 + 11 = 16 y el cliente no paga ni envío ni aduana.
         */
        @Test
        void conSetentaDeGananciaElEnvioYElArancelSalenGRATISYSobranOnce() {
            org.mockito.Mockito.doReturn(new BigDecimal("5.00")).when(currencyService).toUsd(any(), anyString());
            org.mockito.Mockito.doReturn(new CustomsValuation("ES", TaxMode.DDP, 0, false,
                    OverThresholdPolicy.SURCHARGE, 24_00, false, "", false))
                    .when(customsValuation).valuate(any(), anyInt(), anyInt(), anyList());
            org.mockito.Mockito.doAnswer(i -> (int) Math.round((int) i.getArgument(2) * 0.21))
                    .when(taxService).taxCentsFor(any(), any(), anyInt());

            int bolsa = subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, 70_00, 0)), "ES");
            assertThat(bolsa).as("70 de ganancia menos los 5 del suelo").isEqualTo(65_00);

            CheckoutTotals t = totals.compute("ES", null, 120_00, 30_00,
                    List.of(new DutyParcel(120_00, 8)), bolsa);

            assertThat(t.shippingSubsidyPercent()).as("envío cubierto entero").isEqualTo(100);
            assertThat(t.customsSubsidyPercent()).as("arancel cubierto entero").isEqualTo(100);
            assertThat(t.freeShipping()).isTrue();
            assertThat(t.shippingCents()).as("el cliente no paga ni envío ni aduana").isZero();

            assertThat(bolsa - t.subsidyCents()).as("lo que sobra NO se gasta: vuelve a la ganancia")
                    .isEqualTo(11_00);

            // El IVA va sobre lo que de verdad se cobra: la mercancía, sin envío que gravar.
            assertThat(t.taxCents()).isEqualTo(25_20);
            assertThat(t.totalCents(120_00)).isEqualTo(120_00 + 25_20);
        }

        @Test
        void siLaBolsaNoLlegaACubrirloTodoSeEnsenaElPORCENTAJEQueCubre() {
            // Con menos ganancia el envío se cubre a medias y el arancel no se toca: cada concepto lleva
            // su propio porcentaje, que es lo que el comprador necesita para entender qué está pagando.
            org.mockito.Mockito.doReturn(new BigDecimal("5.00")).when(currencyService).toUsd(any(), anyString());
            org.mockito.Mockito.doReturn(new CustomsValuation("ES", TaxMode.DDP, 0, false,
                    OverThresholdPolicy.SURCHARGE, 24_00, false, "", false))
                    .when(customsValuation).valuate(any(), anyInt(), anyInt(), anyList());

            int bolsa = subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, 17_00, 0)), "ES");
            assertThat(bolsa).isEqualTo(12_00);

            CheckoutTotals t = totals.compute("ES", null, 120_00, 30_00,
                    List.of(new DutyParcel(120_00, 8)), bolsa);

            assertThat(t.shippingSubsidyPercent()).isEqualTo(40);
            assertThat(t.customsSubsidyPercent()).as("no llega al arancel: nada").isZero();
            assertThat(t.freeShipping()).isFalse();
            assertThat(t.shippingCents()).isEqualTo(18_00 + 24_00);
        }

        @Test
        void laBolsaSeAgotaEnElENVIOAntesDeTocarElARANCEL() {
            // El orden es el que pidió el dueño: primero el envío entero, y solo lo que sobre al arancel.
            org.mockito.Mockito.doReturn(new BigDecimal("5.00")).when(currencyService).toUsd(any(), anyString());
            org.mockito.Mockito.doReturn(new CustomsValuation("ES", TaxMode.DDP, 0, false,
                    OverThresholdPolicy.SURCHARGE, 24_00, false, "", false))
                    .when(customsValuation).valuate(any(), anyInt(), anyInt(), anyList());

            int bolsa = subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, 41_00, 0)), "ES");
            assertThat(bolsa).isEqualTo(36_00);

            CheckoutTotals t = totals.compute("ES", null, 120_00, 30_00,
                    List.of(new DutyParcel(120_00, 8)), bolsa);

            assertThat(t.shippingSubsidyCents()).isEqualTo(30_00);
            assertThat(t.customsSubsidyCents()).as("los 6 que sobran van al arancel").isEqualTo(6_00);
            assertThat(t.customsSubsidyPercent()).isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("Con decimales incómodos el desglose sigue cuadrando al céntimo")
    class Decimales {

        /** Deja el escenario montado con el arancel indicado y el IVA al 21 % de lo que se le pase. */
        private void escenario(int arancelCents) {
            org.mockito.Mockito.doReturn(new BigDecimal("5.00")).when(currencyService).toUsd(any(), anyString());
            org.mockito.Mockito.doReturn(new CustomsValuation("ES", TaxMode.DDP, 0, false,
                    OverThresholdPolicy.SURCHARGE, arancelCents, false, "", false))
                    .when(customsValuation).valuate(any(), anyInt(), anyInt(), anyList());
            org.mockito.Mockito.doAnswer(i -> (int) Math.round((int) i.getArgument(2) * 0.21))
                    .when(taxService).taxCentsFor(any(), any(), anyInt());
        }

        /** Comprueba la identidad que ve el comprador: lo que suma en pantalla ES lo que se cobra. */
        private void cuadra(CheckoutTotals t, int subtotal) {
            int porteCobrado = t.shippingBaseCents() - t.shippingSubsidyCents();
            int arancelCobrado = t.customsHandlingCents() - t.customsSubsidyCents();
            assertThat(t.shippingCents()).as("envío cobrado = porte + arancel, ya subvencionados")
                    .isEqualTo(porteCobrado + arancelCobrado);
            assertThat(t.totalCents(subtotal))
                    .as("subtotal + porte + arancel − subvenciones + IVA")
                    .isEqualTo(subtotal + porteCobrado + arancelCobrado + t.taxCents());
            assertThat(porteCobrado).isNotNegative();
            assertThat(arancelCobrado).isNotNegative();
            assertThat(t.subsidyCents()).isEqualTo(t.shippingSubsidyCents() + t.customsSubsidyCents());
        }

        @Test
        void carritoDe11999ConPorteDe2997YArancelDe2399() {
            // Todo con céntimos impares: el sitio donde un redondeo mal puesto se lleva un céntimo.
            escenario(23_99);
            int bolsa = subsidy.subsidyUsdCents(List.of(new Linea(PRODUCTO, 1, 70_03, 0)), "ES");
            assertThat(bolsa).as("70,03 menos el suelo de 5,00").isEqualTo(65_03);

            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), bolsa);

            assertThat(t.shippingSubsidyCents()).isEqualTo(29_97);
            assertThat(t.customsSubsidyCents()).isEqualTo(23_99);
            assertThat(t.shippingCents()).isZero();
            assertThat(t.taxCents()).as("IVA sobre la mercancía sola: 21 % de 119,99").isEqualTo(25_20);
            cuadra(t, 119_99);
        }

        @Test
        void laBolsaSeQuedaAUNCENTIMODeCubrirElPorte() {
            // El borde exacto por abajo: 99,99 % no es 100 %, y el envío NO puede decir «gratis».
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), 29_96);

            assertThat(t.shippingSubsidyCents()).isEqualTo(29_96);
            assertThat(t.customsSubsidyCents()).isZero();
            assertThat(t.freeShipping()).as("falta un céntimo: no es gratis").isFalse();
            assertThat(t.shippingSubsidyPercent()).isEqualTo(100);
            cuadra(t, 119_99);
        }

        @Test
        void laBolsaCubreElPorteJUSTOYNiUnCentimoMas() {
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), 29_97);

            assertThat(t.freeShipping()).isTrue();
            assertThat(t.customsSubsidyCents()).isZero();
            assertThat(t.shippingCents()).isEqualTo(23_99);
            cuadra(t, 119_99);
        }

        @Test
        void unSobranteImparSeVaEnteroAlArancel() {
            // 40,00 de bolsa: 29,97 al porte y los 10,03 restantes al arancel, sin perder el céntimo.
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), 40_00);

            assertThat(t.shippingSubsidyCents()).isEqualTo(29_97);
            assertThat(t.customsSubsidyCents()).isEqualTo(10_03);
            assertThat(t.customsSubsidyPercent()).as("10,03 de 23,99 es el 42 %").isEqualTo(42);
            cuadra(t, 119_99);
        }

        @Test
        void unTercioDeUnImporteImparRedondeaAlEnteroMasCercano() {
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), 9_99);

            assertThat(t.shippingSubsidyPercent()).as("9,99 de 29,97 es exactamente un tercio").isEqualTo(33);
            cuadra(t, 119_99);
        }
    }

    @Nested
    @DisplayName("Los bordes que nadie mira")
    class Bordes {

        private void escenario(int arancelCents) {
            org.mockito.Mockito.doReturn(new BigDecimal("5.00")).when(currencyService).toUsd(any(), anyString());
            org.mockito.Mockito.doReturn(new CustomsValuation("ES", TaxMode.DDP, 0, false,
                    OverThresholdPolicy.SURCHARGE, arancelCents, false, "", false))
                    .when(customsValuation).valuate(any(), anyInt(), anyInt(), anyList());
            org.mockito.Mockito.doAnswer(i -> (int) Math.round((int) i.getArgument(2) * 0.21))
                    .when(taxService).taxCentsFor(any(), any(), anyInt());
        }

        @Test
        void sinPorteQueCobrarLaBolsaVaDIRECTAAlArancel() {
            // Envío gratis de fábrica (el transportista no cobra): la bolsa no se pierde, va al arancel.
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 0,
                    List.of(new DutyParcel(119_99, 8)), 10_00);

            assertThat(t.shippingSubsidyCents()).isZero();
            assertThat(t.customsSubsidyCents()).isEqualTo(10_00);
            assertThat(t.freeShipping()).as("sin porte que cubrir no se anuncia «envío gratis»").isFalse();
            assertThat(t.shippingSubsidyPercent()).isZero();
        }

        @Test
        void sinArancelLaBolsaSoloPuedeIrAlPorteYElSobranteNoSeGasta() {
            escenario(0);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 0)), 99_00);

            assertThat(t.shippingSubsidyCents()).isEqualTo(29_97);
            assertThat(t.customsSubsidyCents()).isZero();
            assertThat(t.customsSubsidyPercent()).as("sin arancel, ni porcentaje ni división por cero").isZero();
            assertThat(t.subsidyCents()).isEqualTo(29_97);
        }

        @Test
        void sinNadaQueCobrarNoSeGastaNiUnCentimoDeLaBolsa() {
            escenario(0);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 0, List.of(), 99_00);

            assertThat(t.subsidyCents()).isZero();
            assertThat(t.shippingCents()).isZero();
            assertThat(t.freeShipping()).isFalse();
        }

        @Test
        void unaBolsaNEGATIVANoSumaNiResta() {
            // No debería llegar nunca, pero este cálculo no puede ser quien invente un cobro de más.
            escenario(23_99);
            CheckoutTotals t = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), -50_00);

            assertThat(t.subsidyCents()).isZero();
            assertThat(t.shippingCents()).isEqualTo(29_97 + 23_99);
        }

        @Test
        void elIVASoloSeRECALCULACuandoElPorteDeVerdadCambia() {
            // Sin subvención el impuesto es el de siempre: ni una consulta de más ni un céntimo distinto.
            escenario(23_99);
            CheckoutTotals conBolsa = totals.compute("ES", null, 119_99, 29_97,
                    List.of(new DutyParcel(119_99, 8)), 0);

            assertThat(conBolsa.taxCents()).isEqualTo((int) Math.round((119_99 + 29_97) * 0.21));
        }
    }
}
