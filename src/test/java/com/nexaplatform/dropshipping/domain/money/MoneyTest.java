package com.nexaplatform.dropshipping.domain.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Money — value object DDD")
class MoneyTest {

    @Nested
    @DisplayName("Construcción")
    class Construction {
        @Test
        void of_amount_currency() {
            Money m = Money.of("10.50", "EUR");
            assertThat(m.amount()).isEqualByComparingTo("10.50");
            assertThat(m.currencyCode()).isEqualTo("EUR");
        }

        @Test
        void zero_is_zero() {
            assertThat(Money.zero("USD").isZero()).isTrue();
            assertThat(Money.zero("USD").isPositive()).isFalse();
            assertThat(Money.zero("USD").isNegative()).isFalse();
        }

        @Test
        void cents_factory_preserves_precision() {
            Money m = Money.ofCents(12345, "EUR");
            assertThat(m.amount()).isEqualByComparingTo("123.45");
            assertThat(m.cents()).isEqualTo(12345);
        }

        @Test
        void currency_code_must_be_iso_4217() {
            assertThatThrownBy(() -> Currency.of("EU"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Currency.of("123"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Currency.of("EUR1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void currency_is_normalized_to_uppercase() {
            assertThat(Currency.of("eur").code()).isEqualTo("EUR");
            assertThat(Currency.of(" usd ").code()).isEqualTo("USD");
        }
    }

    @Nested
    @DisplayName("Redondeo comercial (2 decimales, HALF_UP)")
    class CommercialRounding {
        @ParameterizedTest
        @CsvSource({
                "10.123, 10.12",   // < .5 → down
                "10.125, 10.13",   // .5 → up (HALF_UP)
                "10.999, 11.00",   // carry
                "0.001,  0.00",    // sub-cent
                "0.005,  0.01",    // half-cent up
                "-0.005, -0.01",   // half-cent up (negative)
                "9.995,  10.00",
                "12.34,  12.34"    // ya normalizado
        })
        void rounds_half_up_to_two_decimals(String input, String expected) {
            Money m = Money.of(input, "EUR").commercial();
            assertThat(m.amount()).isEqualByComparingTo(expected);
            // Doble check: la escala efectiva es 2.
            assertThat(m.amount().setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(expected);
        }

        @Test
        void plus_applies_commercial_rounding_to_result() {
            // 0.045 + 0.045 = 0.09 (sin redondeo), pero el contrato dice 2 dec.
            Money a = Money.of("0.045", "EUR");
            Money b = Money.of("0.045", "EUR");
            assertThat(a.plus(b).amount()).isEqualByComparingTo("0.09");
        }

        @Test
        void times_scalar_rounds_to_commercial() {
            // 0.10 × 7 / 3 = 0.2333… → 0.23 con HALF_UP
            Money r = Money.of("0.10", "EUR").times(BigDecimal.valueOf(7).divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP));
            assertThat(r.amount()).isEqualByComparingTo("0.23");
        }
    }

    @Nested
    @DisplayName("Aritmética")
    class Arithmetic {
        @Test
        void plus_minus_same_currency() {
            Money a = Money.of("10.00", "EUR");
            Money b = Money.of("3.25", "EUR");
            assertThat(a.plus(b).amount()).isEqualByComparingTo("13.25");
            assertThat(a.minus(b).amount()).isEqualByComparingTo("6.75");
        }

        @Test
        void plus_different_currency_throws() {
            Money a = Money.of("10.00", "EUR");
            Money b = Money.of("3.25", "USD");
            assertThatThrownBy(() -> a.plus(b))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("USD");
        }

        @Test
        void times_int() {
            assertThat(Money.of("2.50", "EUR").times(4).amount()).isEqualByComparingTo("10.00");
        }

        @Test
        void times_decimal() {
            assertThat(Money.of("10.00", "EUR").times(new BigDecimal("0.15")).amount())
                    .isEqualByComparingTo("1.50");
        }

        @Test
        void divide_by_int() {
            assertThat(Money.of("10.00", "EUR").divide(3).amount()).isEqualByComparingTo("3.33");
        }

        @Test
        void divide_by_zero_throws() {
            assertThatThrownBy(() -> Money.of("10.00", "EUR").divide(0))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        void percent_15_pct_of_50() {
            assertThat(Money.of("50.00", "EUR").percent(BigDecimal.valueOf(15)).amount())
                    .isEqualByComparingTo("7.50");
        }

        @Test
        void negate_and_abs() {
            assertThat(Money.of("5.00", "EUR").negate().amount()).isEqualByComparingTo("-5.00");
            assertThat(Money.of("-5.00", "EUR").abs().amount()).isEqualByComparingTo("5.00");
        }
    }

    @Nested
    @DisplayName("Conversión de divisa")
    class Conversion {
        @Test
        void convert_explicit_rate() {
            // 10 EUR a USD con tasa 1.08
            Money eur = Money.of("10.00", "EUR");
            Money usd = eur.convertTo(Currency.of("USD"), new BigDecimal("1.08"));
            assertThat(usd.amount()).isEqualByComparingTo("10.80");
            assertThat(usd.currencyCode()).isEqualTo("USD");
        }

        @Test
        void convert_same_currency_returns_self() {
            Money m = Money.of("10.00", "EUR");
            Money same = m.convertTo(Currency.of("EUR"), new BigDecimal("99")); // rate irrelevante
            assertThat(same).isEqualTo(m);
        }
    }

    @Nested
    @DisplayName("Predicados y comparaciones")
    class Predicates {
        @Test
        void positive_zero_negative() {
            assertThat(Money.of("0.01", "EUR").isPositive()).isTrue();
            assertThat(Money.zero("EUR").isZero()).isTrue();
            assertThat(Money.of("-0.01", "EUR").isNegative()).isTrue();
        }

        @Test
        void comparison_same_currency() {
            Money a = Money.of("10.00", "EUR");
            Money b = Money.of("20.00", "EUR");
            assertThat(b.greaterThan(a)).isTrue();
            assertThat(a.lessThan(b)).isTrue();
            assertThat(a.lessOrEqual(Money.of("10.00", "EUR"))).isTrue();
        }

        @Test
        void comparison_different_currency_throws() {
            assertThatThrownBy(() -> Money.of("10.00", "EUR").compareTo(Money.of("10.00", "USD")))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("Equals / hashCode")
    class Identity {
        @Test
        void equals_normalizes_scale() {
            // 10.00 == 10.0 == 10 (mismo dinero, distinta escala BigDecimal)
            assertThat(Money.of("10.00", "EUR")).isEqualTo(Money.of("10", "EUR"));
            assertThat(Money.of("10.00", "EUR")).isEqualTo(Money.of("10.0", "EUR"));
            assertThat(Money.of("10.00", "EUR")).hasSameHashCodeAs(Money.of("10.0", "EUR"));
        }

        @Test
        void not_equal_different_currency() {
            assertThat(Money.of("10.00", "EUR")).isNotEqualTo(Money.of("10.00", "USD"));
        }

        @Test
        void rounding_normalization_in_equality() {
            // 10.001 redondea a 10.00 → debe ser igual a 10.00 exacto.
            assertThat(Money.of("10.001", "EUR")).isEqualTo(Money.of("10.00", "EUR"));
        }
    }

    @Nested
    @DisplayName("toString consistente")
    class Stringification {
        @Test
        void toString_uses_commercial_scale() {
            assertThat(Money.of("10.123", "EUR").toString()).isEqualTo("10.12 EUR");
            assertThat(Money.of("0", "USD").toString()).isEqualTo("0.00 USD");
        }
    }
}
