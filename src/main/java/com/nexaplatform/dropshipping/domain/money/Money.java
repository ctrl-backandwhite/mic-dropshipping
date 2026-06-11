package com.nexaplatform.dropshipping.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object inmutable que representa una cantidad monetaria con su divisa.
 * <p>
 * Reglas de dominio:
 * <ul>
 *   <li>El amount se almacena con escala arbitraria internamente, pero todo
 *       resultado público de cálculos se redondea con {@link #commercial()} —
 *       2 decimales, HALF_UP (redondeo comercial estándar).</li>
 *   <li>Las operaciones aritméticas requieren la misma divisa; intentar
 *       sumar EUR a USD lanza {@link CurrencyMismatchException} porque
 *       implícitamente conversar divisas sin una tasa explícita es bug
 *       financiero (DROP-conversion).</li>
 *   <li>Multiplicación/división por escalar mantienen la divisa; división por
 *       cero lanza {@link ArithmeticException}.</li>
 *   <li>Equals/HashCode comparan amount NORMALIZADO (2 decimales) — 10.00 EUR
 *       es igual a 10.0 EUR; pero NO igual a 10 USD.</li>
 * </ul>
 * Este objeto se usa como pivote en todos los cálculos de precio (pricing),
 * carrito (cart), wallet (balance/charge/hold) y reportes (GMV/MRR).
 */
public final class Money implements Comparable<Money> {

    /** Escala comercial estándar: 2 decimales (céntimos). */
    public static final int COMMERCIAL_SCALE = 2;
    /** Política de redondeo bancario/comercial estándar: HALF_UP. */
    public static final RoundingMode COMMERCIAL_ROUNDING = RoundingMode.HALF_UP;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /* ==================== Factories ==================== */

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, Currency.of(currencyCode));
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.of(currencyCode));
    }

    public static Money of(double amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), Currency.of(currencyCode));
    }

    /** Construcción a partir de céntimos (long). Útil para wallet/orders donde
     *  el dato canónico viaja en cents para evitar errores de coma flotante. */
    public static Money ofCents(long cents, Currency currency) {
        return new Money(BigDecimal.valueOf(cents, COMMERCIAL_SCALE), currency);
    }

    public static Money ofCents(long cents, String currencyCode) {
        return ofCents(cents, Currency.of(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zero(String currencyCode) {
        return zero(Currency.of(currencyCode));
    }

    /* ==================== Accessors ==================== */

    public BigDecimal amount() { return amount; }
    public Currency currency() { return currency; }
    public String currencyCode() { return currency.code(); }

    /** Cantidad en céntimos como long. Rounding HALF_UP por seguridad. */
    public long cents() {
        return amount.movePointRight(COMMERCIAL_SCALE)
                .setScale(0, COMMERCIAL_ROUNDING)
                .longValueExact();
    }

    /** Devuelve una copia con el amount redondeado al estándar comercial (2 decimales HALF_UP). */
    public Money commercial() {
        if (amount.scale() == COMMERCIAL_SCALE && amount.signum() != 0) return this;
        return new Money(amount.setScale(COMMERCIAL_SCALE, COMMERCIAL_ROUNDING), currency);
    }

    /** Para casos donde se necesita más precisión (intermedios de margen). */
    public Money withScale(int scale, RoundingMode mode) {
        return new Money(amount.setScale(scale, mode), currency);
    }

    /* ==================== Aritmética ==================== */

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency).commercial();
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency).commercial();
    }

    public Money times(int multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency).commercial();
    }

    public Money times(BigDecimal multiplier) {
        Objects.requireNonNull(multiplier, "multiplier");
        return new Money(amount.multiply(multiplier), currency).commercial();
    }

    public Money times(double multiplier) {
        return times(BigDecimal.valueOf(multiplier));
    }

    /** División por escalar; redondeo HALF_UP a 2 decimales. */
    public Money divide(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.signum() == 0) throw new ArithmeticException("Division by zero");
        return new Money(amount.divide(divisor, COMMERCIAL_SCALE, COMMERCIAL_ROUNDING), currency);
    }

    public Money divide(int divisor) {
        return divide(BigDecimal.valueOf(divisor));
    }

    /** Aplica un porcentaje (e.g. 15% = 15) sobre el monto. */
    public Money percent(BigDecimal pct) {
        Objects.requireNonNull(pct, "pct");
        return times(pct.divide(BigDecimal.valueOf(100), 6, COMMERCIAL_ROUNDING));
    }

    public Money negate() {
        return new Money(amount.negate(), currency).commercial();
    }

    public Money abs() {
        return new Money(amount.abs(), currency).commercial();
    }

    /* ==================== Predicados ==================== */

    public boolean isZero()     { return amount.signum() == 0; }
    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isNegative() { return amount.signum() < 0; }

    public boolean greaterThan(Money other)  { requireSameCurrency(other); return amount.compareTo(other.amount) > 0; }
    public boolean greaterOrEqual(Money other){ requireSameCurrency(other); return amount.compareTo(other.amount) >= 0; }
    public boolean lessThan(Money other)     { requireSameCurrency(other); return amount.compareTo(other.amount) < 0; }
    public boolean lessOrEqual(Money other)  { requireSameCurrency(other); return amount.compareTo(other.amount) <= 0; }

    /* ==================== Conversión de divisa ==================== */

    /**
     * Convierte a otra divisa aplicando una tasa explícita.
     * Se exige tasa explícita: nunca convertimos implícitamente porque oculta
     * dependencias de rates rancios.
     *
     * @param target  divisa destino
     * @param rate    cuánto vale 1 unidad de la divisa actual en la destino
     */
    public Money convertTo(Currency target, BigDecimal rate) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(rate, "rate");
        if (currency.equals(target)) return this;
        return new Money(amount.multiply(rate), target).commercial();
    }

    /* ==================== Helpers ==================== */

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return currency.equals(m.currency)
                && commercial().amount.compareTo(m.commercial().amount) == 0;
    }

    @Override
    public int hashCode() {
        // Hash sobre amount normalizado a 2 decimales para coherencia con equals.
        return Objects.hash(commercial().amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return commercial().amount.toPlainString() + " " + currency.code();
    }
}
