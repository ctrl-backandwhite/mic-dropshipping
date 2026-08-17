package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryCustomsRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CountryCustomsRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Límite de ACEPTACIÓN del transportista, que no es el umbral fiscal.
 *
 * <p>Son dos cosas distintas y el sistema solo tenía una:
 *
 * <ul>
 *   <li><b>Fiscal</b> (Reglamento UE): el régimen de bajo valor aplica cuando el valor «no excede»
 *       150 EUR, así que 150,00 EUR exactos están DENTRO y pagan sus 3 EUR por partida. Eso lo prueba
 *       {@code CustomsRegulationIT} con las citas del Reglamento y aquí no se toca.</li>
 *   <li><b>Aceptación</b> (contrato YunExpress): «不接受等于和大于150欧元或155美金的包裹» — no acepta
 *       paquetes iguales o mayores a 150 EUR o a 155 USD. Es más estricto que la norma.</li>
 * </ul>
 *
 * <p>El caso que protege este test es el borde exacto: 150,00 EUR pasaban el checkout, se cobraban, y
 * el transportista los rechazaba después.
 */
class CarrierAcceptanceLimitTest {

    private CountryCustomsRuleRepository repository;
    private CurrencyRateService currencyService;
    private CustomsValuationService service;

    /** 1 EUR = 1,10 USD: con esta tasa el tope que manda es el de 155 USD (150 EUR serían 165). */
    private static final BigDecimal EUR_USD = new BigDecimal("1.10");

    @BeforeEach
    void setUp() {
        repository = mock(CountryCustomsRuleRepository.class);
        currencyService = mock(CurrencyRateService.class);
        service = new CustomsValuationService(repository, currencyService);
        // Sin tasa registrada, el importe se toma tal cual como USD; con ella, se convierte.
        when(currencyService.find(anyString())).thenReturn(Optional.empty());
        when(currencyService.find("EUR")).thenReturn(Optional.of(new CurrencyRateEntity()));
        when(currencyService.toUsd(any(BigDecimal.class), eq("EUR")))
                .thenAnswer(inv -> inv.getArgument(0, BigDecimal.class).multiply(EUR_USD));
    }

    private void reglaEspanola(String maxAmount, String maxCurrency, String altAmount, String altCurrency) {
        CountryCustomsRuleEntity r = new CountryCustomsRuleEntity();
        r.setCountryCode("ES");
        r.setTaxMode("DDP");
        r.setDeMinimisAmount(new BigDecimal("150.00"));
        r.setDeMinimisCurrency("EUR");
        r.setOverThresholdPolicy("BLOCK");
        r.setPerArticleFeeAmount(new BigDecimal("3.00"));
        r.setPerArticleFeeCurrency("EUR");
        r.setActive(true);
        r.setCarrierMaxAmount(new BigDecimal(maxAmount));
        r.setCarrierMaxCurrency(maxCurrency);
        r.setCarrierMaxAltAmount(new BigDecimal(altAmount));
        r.setCarrierMaxAltCurrency(altCurrency);
        when(repository.findByCountryCodeIgnoreCase("ES")).thenReturn(Optional.of(r));
    }

    private CustomsValuation valorar(int intrinsicCents) {
        return service.valuate("ES", intrinsicCents, 0, List.of());
    }

    @Test
    @DisplayName("el borde exacto del transportista se bloquea aunque siga dentro del régimen fiscal")
    void bloqueaEnElBordeExactoDelTransportista() {
        reglaEspanola("150.00", "EUR", "0", "USD");

        // 150 EUR × 1,10 = 165,00 USD. El transportista rechaza a partir de ahí, inclusive.
        CustomsValuation enElBorde = valorar(16_500);

        assertThat(enElBorde.blocked()).isTrue();
        // Y sin embargo NO excede la franquicia fiscal: sigue siendo un envío de bajo valor.
        assertThat(enElBorde.deMinimisExceeded()).isFalse();
    }

    @Test
    @DisplayName("un céntimo por debajo del tope del transportista sí se acepta")
    void aceptaJustoPorDebajoDelTope() {
        reglaEspanola("150.00", "EUR", "0", "USD");

        assertThat(valorar(16_499).blocked()).isFalse();
    }

    @Test
    @DisplayName("cuando hay dos topes gana el más restrictivo")
    void ganaElTopeMasRestrictivo() {
        // 150 EUR = 165 USD, pero el contrato impone además 155 USD: manda el segundo.
        reglaEspanola("150.00", "EUR", "155.00", "USD");

        assertThat(valorar(15_500).blocked()).isTrue();
        assertThat(valorar(15_499).blocked()).isFalse();
    }

    @Test
    @DisplayName("el mensaje enseña el tope que de verdad bloqueó, no el fiscal")
    void elMensajeEnsenaElTopeQueBloqueo() {
        reglaEspanola("150.00", "EUR", "155.00", "USD");

        // Bloquea el tope del transportista en dólares: decir «supera 150 EUR» sobre un pedido de
        // 155 USD (=140,90 EUR) sería contradictorio para quien lo lee.
        assertThat(valorar(15_500).deMinimisLabel()).isEqualTo("155 USD");

        // Y cuando el bloqueo es fiscal, se sigue enseñando la franquicia del país.
        reglaEspanola("0", "USD", "0", "USD");
        assertThat(valorar(16_502).deMinimisLabel()).isEqualTo("150 EUR");
    }

    @Test
    @DisplayName("sin límite configurado el transportista no bloquea nada")
    void sinLimiteNoBloquea() {
        // 0 = no configurado, igual que en el resto de la tabla: no se inventa un tope que nadie ha dado.
        reglaEspanola("0", "USD", "0", "USD");

        // 160 USD: por debajo de la franquicia (150 EUR = 165 USD), así que tampoco entra el bloqueo
        // fiscal y se ve el efecto de no tener límite de transportista.
        assertThat(valorar(16_000).blocked()).isFalse();
    }

    @Test
    @DisplayName("el bloqueo por umbral fiscal sigue funcionando por su cuenta")
    void elBloqueoFiscalSigueVivo() {
        reglaEspanola("0", "USD", "0", "USD");

        // 150,01 EUR = 165,011 USD → excede la franquicia y la política del país es BLOCK.
        CustomsValuation excedido = valorar(16_502);

        assertThat(excedido.deMinimisExceeded()).isTrue();
        assertThat(excedido.blocked()).isTrue();
    }
}
