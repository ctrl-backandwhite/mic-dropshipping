package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryCustomsRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryCustomsRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Reglas de despacho aduanero por país: valor declarado, umbral de minimis y recargo del DDP.
 */
@ExtendWith(MockitoExtension.class)
class CustomsValuationServiceTest {

    @Mock
    CountryCustomsRuleRepository repository;
    @Mock
    CurrencyRateService currencyService;
    @InjectMocks
    CustomsValuationService service;

    /** Regla base: DDP, umbral 150 EUR, política de recargo, sin importes de recargo configurados. */
    private static CountryCustomsRuleEntity rule(String country) {
        return CountryCustomsRuleEntity.builder().countryCode(country).taxMode("DDP")
                .deMinimisAmount(new BigDecimal("150")).deMinimisCurrency("EUR").overThresholdPolicy("SURCHARGE")
                .handlingFeeCents(0).handlingPercentBps(0).overThresholdSurchargeCents(0).dutyRateBps(0).active(true)
                .build();
    }

    private void givenRule(CountryCustomsRuleEntity r) {
        when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.of(r));
    }

    /** 1 EUR = 1,10 USD → el umbral de 150 EUR equivale a 165 USD. */
    private void givenEurRate() {
        CurrencyRateEntity eur = new CurrencyRateEntity();
        eur.setCode("EUR");
        eur.setRateVsUsd(new BigDecimal("0.909091"));
        lenient().when(currencyService.find("EUR")).thenReturn(Optional.of(eur));
        lenient().when(currencyService.toUsd(new BigDecimal("150"), "EUR")).thenReturn(new BigDecimal("165.0000"));
    }

    @Test
    void sin_regla_configurada_la_valoracion_es_neutra() {
        when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.empty());

        CustomsValuation v = service.valuate("ZZ", 50_00, 10_00, List.of(new DutyParcel(Math.max(0, 50_00), 1)));

        assertThat(v.taxMode()).isEqualTo(TaxMode.DDP);
        assertThat(v.handlingFeeCents()).isZero();
        assertThat(v.deMinimisExceeded()).isFalse();
        assertThat(v.blocked()).isFalse();
    }

    // ===== 1) Valor declarado =====

    @Test
    void el_valor_declarado_es_el_valor_intrinseco_de_los_bienes() {
        givenRule(rule("ES"));
        givenEurRate();

        CustomsValuation v = service.valuate("ES", 120_00, 25_20, List.of(new DutyParcel(Math.max(0, 120_00), 1)));

        // Lo que se declara es lo que el cliente paga por los bienes, no el coste de compra al proveedor.
        assertThat(v.declaredValueCents()).isEqualTo(120_00);
        assertThat(v.intrinsicValueCents()).isEqualTo(120_00);
    }

    @Test
    void un_valor_intrinseco_negativo_se_normaliza_a_cero() {
        givenRule(rule("ES"));
        givenEurRate();

        assertThat(service.valuate("ES", -5_00, 0, List.of(new DutyParcel(Math.max(0, -5_00), 1))).declaredValueCents())
                .isZero();
    }

    // ===== 2) Umbral de minimis por país =====

    @Test
    void por_debajo_del_umbral_del_pais_no_se_marca_excedido() {
        givenRule(rule("ES"));
        givenEurRate();

        // 164 USD < 165 USD (equivalente de 150 EUR)
        assertThat(
                service.valuate("ES", 164_00, 0, List.of(new DutyParcel(Math.max(0, 164_00), 1))).deMinimisExceeded())
                .isFalse();
    }

    @Test
    void por_encima_del_umbral_del_pais_se_marca_excedido() {
        givenRule(rule("ES"));
        givenEurRate();

        assertThat(
                service.valuate("ES", 200_00, 0, List.of(new DutyParcel(Math.max(0, 200_00), 1))).deMinimisExceeded())
                .isTrue();
    }

    @Test
    void el_umbral_en_usd_no_necesita_conversion() {
        CountryCustomsRuleEntity r = rule("MX");
        r.setDeMinimisAmount(new BigDecimal("50"));
        r.setDeMinimisCurrency("USD");
        givenRule(r);

        assertThat(service.valuate("MX", 49_00, 0, List.of(new DutyParcel(Math.max(0, 49_00), 1))).deMinimisExceeded())
                .isFalse();
        assertThat(service.valuate("MX", 51_00, 0, List.of(new DutyParcel(Math.max(0, 51_00), 1))).deMinimisExceeded())
                .isTrue();
    }

    @Test
    void umbral_cero_significa_no_configurado_y_no_encarece_ningun_pedido() {
        CountryCustomsRuleEntity r = rule("FJ");
        r.setDeMinimisAmount(BigDecimal.ZERO);
        r.setOverThresholdSurchargeCents(9_99);
        givenRule(r);

        CustomsValuation v = service.valuate("FJ", 5_000_00, 0, List.of(new DutyParcel(Math.max(0, 5_000_00), 1)));

        assertThat(v.deMinimisExceeded()).isFalse();
        assertThat(v.handlingFeeCents()).isZero();
    }

    @Test
    void divisa_de_umbral_no_disponible_se_interpreta_como_usd() {
        CountryCustomsRuleEntity r = rule("NO");
        r.setDeMinimisAmount(new BigDecimal("3000"));
        r.setDeMinimisCurrency("NOK");
        givenRule(r);
        when(currencyService.find("NOK")).thenReturn(Optional.empty());

        assertThat(service.valuate("NO", 2_999_00, 0, List.of(new DutyParcel(Math.max(0, 2_999_00), 1)))
                .deMinimisExceeded()).isFalse();
        assertThat(service.valuate("NO", 3_001_00, 0, List.of(new DutyParcel(Math.max(0, 3_001_00), 1)))
                .deMinimisExceeded()).isTrue();
    }

    @Test
    void politica_block_impide_el_pedido_por_encima_del_umbral() {
        CountryCustomsRuleEntity r = rule("BR");
        r.setOverThresholdPolicy("BLOCK");
        givenRule(r);
        givenEurRate();

        assertThat(service.valuate("BR", 200_00, 0, List.of(new DutyParcel(Math.max(0, 200_00), 1))).blocked())
                .isTrue();
        assertThat(service.valuate("BR", 100_00, 0, List.of(new DutyParcel(Math.max(0, 100_00), 1))).blocked())
                .isFalse();
    }

    @Test
    void politica_allow_no_aplica_recargo_aunque_supere_el_umbral() {
        CountryCustomsRuleEntity r = rule("DE");
        r.setOverThresholdPolicy("ALLOW");
        r.setOverThresholdSurchargeCents(12_00);
        r.setDutyRateBps(1200);
        givenRule(r);
        givenEurRate();

        CustomsValuation v = service.valuate("DE", 200_00, 42_00, List.of(new DutyParcel(Math.max(0, 200_00), 1)));

        assertThat(v.deMinimisExceeded()).isTrue();
        assertThat(v.handlingFeeCents()).isZero();
        assertThat(v.blocked()).isFalse();
    }

    // ===== 3) Handling fee del DDP =====

    @Test
    void el_handling_fee_suma_parte_fija_y_porcentaje_sobre_el_impuesto() {
        CountryCustomsRuleEntity r = rule("FR");
        r.setHandlingFeeCents(1_50);
        r.setHandlingPercentBps(250); // 2,5% del impuesto
        givenRule(r);
        givenEurRate();

        // impuesto 40,00 USD → 1,50 + 1,00 = 2,50
        assertThat(service.valuate("FR", 100_00, 40_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)))
                .handlingFeeCents()).isEqualTo(2_50);
    }

    @Test
    void la_comision_de_prepago_de_iva_suma_2pct_del_valor_declarado() {
        // Sin IOSS: el carrier adelanta el IVA y cobra un 2% sobre el valor declarado, además del IVA.
        CountryCustomsRuleEntity r = rule("ES");
        r.setVatPrepayPercentBps(200); // 2%
        givenRule(r);
        givenEurRate();

        // 2% de 100,00 USD declarados = 2,00 (el nº de artículos no influye aquí)
        assertThat(service.valuate("ES", 100_00, 0, List.of(new DutyParcel(Math.max(0, 100_00), 1))).handlingFeeCents())
                .isEqualTo(2_00);
    }

    @Test
    void el_arancel_por_articulo_multiplica_la_tarifa_por_producto_distinto() {
        // Arancel temporal de la UE: 3 EUR por artículo (producto distinto). 3 productos = 9 EUR.
        CountryCustomsRuleEntity r = rule("ES");
        r.setPerArticleFeeAmount(new BigDecimal("3.00"));
        r.setPerArticleFeeCurrency("EUR");
        givenRule(r);
        givenEurRate();
        when(currencyService.toUsd(new BigDecimal("3.00"), "EUR")).thenReturn(new BigDecimal("3.3000"));

        // 3 EUR → 3,30 USD por artículo × 3 productos distintos = 9,90
        assertThat(service.valuate("ES", 100_00, 0, List.of(new DutyParcel(Math.max(0, 100_00), 3))).handlingFeeCents())
                .isEqualTo(9_90);
    }

    @Test
    void al_superar_el_umbral_se_anade_despacho_formal_y_arancel_estimado() {
        CountryCustomsRuleEntity r = rule("IT");
        r.setHandlingFeeCents(1_00);
        r.setOverThresholdSurchargeCents(15_00);
        r.setDutyRateBps(1200); // 12% sobre el valor intrínseco
        givenRule(r);
        givenEurRate();

        // 1,00 fijo + 15,00 despacho formal + 12% de 200,00 = 24,00 → 40,00
        assertThat(service.valuate("IT", 200_00, 0, List.of(new DutyParcel(Math.max(0, 200_00), 1))).handlingFeeCents())
                .isEqualTo(40_00);
    }

    @Test
    void en_ddu_no_hay_recargo_porque_el_impuesto_lo_paga_el_destinatario() {
        CountryCustomsRuleEntity r = rule("UA");
        r.setTaxMode("DDU");
        r.setHandlingFeeCents(5_00);
        r.setHandlingPercentBps(500);
        givenRule(r);
        givenEurRate();

        CustomsValuation v = service.valuate("UA", 100_00, 20_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)));

        assertThat(v.taxMode()).isEqualTo(TaxMode.DDU);
        assertThat(v.handlingFeeCents()).isZero();
    }

    @Test
    void una_regla_desactivada_se_ignora() {
        CountryCustomsRuleEntity r = rule("PL");
        r.setActive(false);
        r.setHandlingFeeCents(9_99);
        when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.of(r));

        assertThat(service.valuate("PL", 100_00, 10_00, List.of(new DutyParcel(Math.max(0, 100_00), 1)))
                .handlingFeeCents()).isZero();
    }

    @Test
    void tax_mode_por_pais_cae_a_ddp_si_no_hay_regla() {
        when(repository.findByCountryCodeIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThat(service.taxModeFor("ZZ")).isEqualTo(TaxMode.DDP);
    }

    @Test
    void pais_nulo_o_vacio_no_resuelve_regla() {
        assertThat(service.rule(null)).isEmpty();
        assertThat(service.rule("  ")).isEmpty();
    }

    @Test
    void valor_desconocido_de_politica_cae_a_surcharge() {
        assertThat(OverThresholdPolicy.from("LO_QUE_SEA")).isEqualTo(OverThresholdPolicy.SURCHARGE);
        assertThat(OverThresholdPolicy.from(null)).isEqualTo(OverThresholdPolicy.SURCHARGE);
        assertThat(TaxMode.from("LO_QUE_SEA")).isEqualTo(TaxMode.DDP);
        assertThat(TaxMode.from(null)).isEqualTo(TaxMode.DDP);
    }

    // ================== Destinos SIN franquicia (v153: US y PR) ==================

    /**
     * El caso que motivó la v153: «sin franquicia» se escribía igual que «franquicia sin averiguar»
     * ({@code de_minimis_amount = 0}) y el motor solo entendía la segunda, así que Estados Unidos no
     * cobraba arancel ni bloqueaba nada. Aquí el importe es de un céntimo y aun así cuenta como superado.
     */
    @Test
    void sin_franquicia_cualquier_importe_cuenta_como_superado() {
        CountryCustomsRuleEntity r = rule("US");
        r.setDeMinimisAmount(BigDecimal.ZERO);
        r.setDeMinimisCurrency("USD");
        r.setDeMinimisApplies(false);
        givenRule(r);

        assertThat(service.valuate("US", 1, 0, List.of(new DutyParcel(1, 1))).deMinimisExceeded()).isTrue();
        assertThat(service.valuate("US", 5_000_00, 0, List.of(new DutyParcel(5_000_00, 1))).deMinimisExceeded())
                .isTrue();
    }

    /** Con BLOCK, un destino sin franquicia no acepta ningún pedido: se rechaza antes de cobrar. */
    @Test
    void sin_franquicia_con_block_rechaza_cualquier_pedido() {
        CountryCustomsRuleEntity r = rule("US");
        r.setDeMinimisAmount(BigDecimal.ZERO);
        r.setDeMinimisCurrency("USD");
        r.setDeMinimisApplies(false);
        r.setOverThresholdPolicy("BLOCK");
        givenRule(r);

        assertThat(service.valuate("US", 1, 0, List.of(new DutyParcel(1, 1))).blocked()).isTrue();
        assertThat(service.valuate("US", 20_00, 0, List.of(new DutyParcel(20_00, 1))).blocked()).isTrue();
    }

    /**
     * El derecho fijo por línea es el de la UE por DEBAJO de su franquicia. Un destino que no tiene
     * franquicia está siempre por encima, así que no le corresponde: si se cobrara, se estaría aplicando
     * a Estados Unidos una tarifa que solo existe en el reglamento europeo.
     */
    @Test
    void sin_franquicia_no_se_cobra_el_derecho_por_linea_de_la_ue() {
        CountryCustomsRuleEntity r = rule("US");
        r.setDeMinimisAmount(BigDecimal.ZERO);
        r.setDeMinimisCurrency("USD");
        r.setDeMinimisApplies(false);
        r.setPerArticleFeeAmount(new BigDecimal("3"));
        r.setPerArticleFeeCurrency("USD");
        givenRule(r);

        assertThat(service.perLineDutyUsdCents("US", List.of(new DutyParcel(20_00, 4)))).isZero();
    }

    /** Un país con franquicia real no cambia de comportamiento: la marca nueva no toca a los otros 84. */
    @Test
    void los_paises_con_franquicia_siguen_evaluando_su_umbral() {
        CountryCustomsRuleEntity r = rule("ES");
        givenRule(r);
        givenEurRate();

        assertThat(service.valuate("ES", 100_00, 0, List.of(new DutyParcel(100_00, 1))).deMinimisExceeded()).isFalse();
        assertThat(service.valuate("ES", 200_00, 0, List.of(new DutyParcel(200_00, 1))).deMinimisExceeded()).isTrue();
    }

    /**
     * La columna trae {@code DEFAULT TRUE} en la base, pero el ORM nombra todas las columnas en el INSERT
     * y ese defecto no llega a aplicarse. Sin el {@code @Builder.Default} de la entidad, dar de alta un
     * país desde el panel lo dejaría sin franquicia y bloqueado sin que nadie lo hubiera pedido.
     */
    @Test
    void una_regla_nueva_nace_con_franquicia_para_no_bloquear_el_pais_sin_querer() {
        assertThat(CountryCustomsRuleEntity.builder().countryCode("ZZ").build().isDeMinimisApplies()).isTrue();
    }
}
