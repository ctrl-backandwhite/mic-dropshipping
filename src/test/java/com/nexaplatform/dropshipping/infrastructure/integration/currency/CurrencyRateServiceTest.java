package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrencyRateServiceTest {

    @Mock
    private CurrencyRateRepository repository;

    private CurrencyRateService service;

    private static CurrencyRateEntity rate(String code, String symbol, String locale, String rateVsUsd,
            boolean active) {
        CurrencyRateEntity e = CurrencyRateEntity.builder().code(code).name(code).symbol(symbol).locale(locale)
                .rateVsUsd(new BigDecimal(rateVsUsd)).active(active).build();
        return e;
    }

    @BeforeEach
    void setUp() {
        service = new CurrencyRateService(repository);
        // @PostConstruct.warm() is NOT invoked under plain Mockito; the cache is empty and
        // cacheStamp = Instant.EPOCH, so the first public read triggers ensureFresh() -> refreshCache().
        lenient().when(repository.findAll()).thenReturn(List.of(
                rate("USD", "$", "en-US", "1.00000000", true),
                rate("EUR", "€", "es-ES", "0.90000000", true),
                rate("CNY", "¥", "zh-CN", "7.20000000", true),
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
    void usdTo_usd_keeps_amount_with_two_decimals_rounded_up() {
        assertThat(service.usdTo(new BigDecimal("10.001"), "USD")).isEqualByComparingTo("10.01");
    }

    @Test
    void usdTo_other_currency_multiplies_by_rate_and_rounds_up() {
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
    void toUsd_unknown_source_throws_not_found() {
        assertThatThrownBy(() -> service.toUsd(new BigDecimal("1"), "XXX")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void toUsd_null_amount_returns_null() {
        assertThat(service.toUsd(null, "EUR")).isNull();
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
