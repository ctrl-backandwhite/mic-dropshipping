package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CurrencyRateServiceTest {

    @Mock
    private CurrencyRateRepository repository;

    @InjectMocks
    private CurrencyRateService service;

    private static CurrencyRateEntity rate(String code, String symbol, String locale, String rateVsUsd,
            boolean active) {
        return CurrencyRateEntity.builder().code(code).name(code).symbol(symbol).locale(locale)
                .rateVsUsd(new BigDecimal(rateVsUsd)).active(active).build();
    }

    @BeforeEach
    void setUp() {
        // @PostConstruct.warm() is NOT invoked under plain Mockito; the cache is empty and
        // cacheStamp = Instant.EPOCH, so the first public read triggers ensureFresh() -> refreshCache().
        lenient().when(repository.findAll())
                .thenReturn(List.of(rate("USD", "$", "en-US", "1.00000000", true),
                        rate("EUR", "€", "es-ES", "0.90000000", true), rate("CNY", "¥", "zh-CN", "7.20000000", true),
                        rate("GBP", "£", "en-GB", "0.80000000", false)));
    }

    @AfterEach
    void tearDown() {
        CurrencyHolder.clear();
    }

    /* ============ cache reads ============ */

    @Test
    void listActive_returns_only_active_sorted_by_code() {
        List<CurrencyRateEntity> active = service.listActive();

        assertThat(active).extracting(CurrencyRateEntity::getCode).containsExactly("CNY", "EUR", "USD");
    }

    @Test
    void listAll_returns_active_and_inactive_sorted_by_code() {
        List<CurrencyRateEntity> all = service.listAll();

        assertThat(all).extracting(CurrencyRateEntity::getCode).containsExactly("CNY", "EUR", "GBP", "USD");
    }

    @Test
    void find_is_case_insensitive_and_empty_for_null_or_unknown() {
        assertThat(service.find("eur")).isPresent();
        assertThat(service.find(null)).isEmpty();
        assertThat(service.find("XXX")).isEmpty();
    }

    @Test
    void require_throws_not_found_for_unknown_code() {
        assertThatThrownBy(() -> service.require("XXX")).isInstanceOf(NotFoundException.class);
    }

    /* ============ usdTo / toUsd ============ */

    @Test
    void usdTo_usd_rounds_two_decimals_half_up() {
        // HALF_UP al céntimo más cercano (no UP): 10.004 -> 10.00, 10.005 -> 10.01, 10.006 -> 10.01.
        assertThat(service.usdTo(new BigDecimal("10.004"), "USD")).isEqualByComparingTo("10.00");
        assertThat(service.usdTo(new BigDecimal("10.005"), "USD")).isEqualByComparingTo("10.01");
        assertThat(service.usdTo(new BigDecimal("10.006"), "USD")).isEqualByComparingTo("10.01");
    }

    @Test
    void usdTo_exact_amount_stays_exact_no_extra_cents() {
        // Un monto ya "cerrado" no debe ganar céntimos.
        assertThat(service.usdTo(new BigDecimal("30.00"), "USD")).isEqualByComparingTo("30.00");
        assertThat(service.usdTo(new BigDecimal("10"), "USD")).isEqualByComparingTo("10.00");
    }

    @Test
    void usdTo_other_currency_multiplies_by_rate_half_up() {
        // 100 USD * 0.90 = 90.00 EUR
        assertThat(service.usdTo(new BigDecimal("100"), "EUR")).isEqualByComparingTo("90.00");
        // 10 USD * 7.20 = 72.00 CNY
        assertThat(service.usdTo(new BigDecimal("10"), "CNY")).isEqualByComparingTo("72.00");
    }

    @Test
    void usdTo_unknown_currency_falls_back_to_usd_amount_scaled() {
        assertThat(service.usdTo(new BigDecimal("5.555"), "XXX")).isEqualByComparingTo("5.56");
    }

    @Test
    void usdTo_null_amount_returns_null() {
        assertThat(service.usdTo(null, "EUR")).isNull();
    }

    @Test
    void usdToDisplay_uses_currency_holder() {
        CurrencyHolder.set("EUR");
        assertThat(service.usdToDisplay(new BigDecimal("100"))).isEqualByComparingTo("90.00");
    }

    @Test
    void toUsd_usd_keeps_four_decimals_half_up() {
        assertThat(service.toUsd(new BigDecimal("10"), "USD")).isEqualByComparingTo("10.0000");
    }

    @Test
    void toUsd_other_currency_divides_by_rate_half_up() {
        // 90 EUR / 0.90 = 100.0000 USD
        assertThat(service.toUsd(new BigDecimal("90"), "EUR")).isEqualByComparingTo("100.0000");
    }

    @Test
    void toUsd_sinTasaDeOrigen_esErrorDeConfiguracionNoUnNoEncontrado() {
        // Que falte la tasa de la divisa en la que está guardado el producto es un fallo de configuración
        // del servidor, no un «recurso no encontrado». Devolvía NotFoundException y el manejador lo
        // traducía a 404, así que TODA lectura de catálogo respondía «no existe» sin que nadie supiera por
        // qué. Como error de estado sale con 500 y queda en el log de errores.
        BigDecimal uno = new BigDecimal("1");
        assertThatThrownBy(() -> service.toUsd(uno, "XXX")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XXX");
    }

    @Test
    void toUsd_null_amount_returns_null() {
        assertThat(service.toUsd(null, "EUR")).isNull();
    }

    /* ============ round-trip / coherencia (origen exacto -> exacto) ============ */

    /**
     * Requisito del usuario: un monto EXACTO en la moneda de origen debe volver EXACTO tras el round-trip
     * moneda -> USD -> moneda, sin céntimos de más ni de menos. Se prueban montos "cerrados" típicos de carga
     * (precio base, envío 10, IVA) en varias monedas contra el dólar.
     */
    @Test
    void roundTrip_exact_amounts_return_exact_for_every_currency() {
        for (String code : List.of("USD", "EUR", "CNY")) {
            for (String amt : List.of("30", "10", "3.90", "60", "0.83", "1240.74")) {
                BigDecimal original = new BigDecimal(amt);
                BigDecimal usd = service.toUsd(original, code);
                BigDecimal back = service.usdTo(usd, code);
                assertThat(back).as("round-trip %s %s -> USD %s -> %s", amt, code, usd, back)
                        .isEqualByComparingTo(original.setScale(2));
            }
        }
    }

    @Test
    void roundTrip_shipping_and_iva_stay_exact_in_cny() {
        // 10 CNY (envío) y 3.90 CNY (IVA) deben mostrarse exactos tras convertir a USD y volver.
        assertThat(service.usdTo(service.toUsd(new BigDecimal("10"), "CNY"), "CNY")).isEqualByComparingTo("10.00");
        assertThat(service.usdTo(service.toUsd(new BigDecimal("3.90"), "CNY"), "CNY")).isEqualByComparingTo("3.90");
    }

    @Test
    void sameCurrency_usd_is_identity() {
        // USD -> USD no debe alterar el valor (misma moneda, tasa 1).
        BigDecimal usd = service.toUsd(new BigDecimal("57.92"), "USD");
        assertThat(service.usdTo(usd, "USD")).isEqualByComparingTo("57.92");
    }

    @Test
    void conversion_rate_is_symmetric_between_two_currencies() {
        // EUR -> USD -> EUR y CNY -> USD -> CNY conservan el valor exacto (tasas inversas coherentes).
        assertThat(service.usdTo(service.toUsd(new BigDecimal("90"), "EUR"), "EUR")).isEqualByComparingTo("90.00");
        assertThat(service.usdTo(service.toUsd(new BigDecimal("72"), "CNY"), "CNY")).isEqualByComparingTo("72.00");
    }

    /* ============ symbol / locale ============ */

    @Test
    void symbolOf_returns_currency_symbol_or_dollar_fallback() {
        assertThat(service.symbolOf("EUR")).isEqualTo("€");
        assertThat(service.symbolOf("XXX")).isEqualTo("$");
    }

    @Test
    void localeOf_returns_locale_or_en_us_fallback() {
        assertThat(service.localeOf("CNY")).isEqualTo("zh-CN");
        assertThat(service.localeOf("XXX")).isEqualTo("en-US");
    }

    /* ============ formatDisplay ============ */

    @Test
    void formatDisplay_formats_with_currency_locale_and_two_decimals() {
        // es-ES/EUR -> "28,26 €" (NBSP before symbol)
        String formatted = service.formatDisplay(new BigDecimal("28.26"), "EUR");
        assertThat(formatted).contains("28,26").contains("€");

        // en-US/USD -> "$32.56"
        assertThat(service.formatDisplay(new BigDecimal("32.56"), "USD")).isEqualTo("$32.56");
    }

    @Test
    void formatDisplay_null_amount_or_code_returns_null() {
        assertThat(service.formatDisplay(null, "EUR")).isNull();
        assertThat(service.formatDisplay(new BigDecimal("1"), null)).isNull();
    }

    @Test
    void formatDisplayRounded_rounds_half_up_to_whole_number() {
        // 25.28 -> "25 €" (no decimals), HALF_UP
        String formatted = service.formatDisplayRounded(new BigDecimal("25.28"), "EUR");
        assertThat(formatted).contains("25").doesNotContain("25,").contains("€");

        // 25.50 -> 26 (HALF_UP)
        assertThat(service.formatDisplayRounded(new BigDecimal("25.50"), "USD")).isEqualTo("$26");
    }

    @Test
    void asPercentage_divides_by_hundred_half_up() {
        assertThat(service.asPercentage(new BigDecimal("150"))).isEqualByComparingTo("1.5000");
        assertThat(service.asPercentage(null)).isNull();
    }
}
