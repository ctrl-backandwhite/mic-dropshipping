package com.nexaplatform.dropshipping.domain.money;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object para representar una divisa ISO 4217. Inmutable y comparable
 * sólo por código (3 letras mayúsculas).
 * <p>
 * Centralizamos aquí la validación para que cualquier {@link Money} que se
 * construya con un string mal formado falle al momento, no más adelante en
 * el cálculo de margen o el cobro Stripe.
 */
public final class Currency {

    private final String code;

    private Currency(String code) {
        this.code = code;
    }

    public static Currency of(String code) {
        Objects.requireNonNull(code, "currency code");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3 || !normalized.chars().allMatch(Character::isLetter)) {
            throw new IllegalArgumentException("Invalid ISO 4217 currency code: " + code);
        }
        return new Currency(normalized);
    }

    public String code() { return code; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Currency c)) return false;
        return code.equals(c.code);
    }

    @Override
    public int hashCode() { return code.hashCode(); }

    @Override
    public String toString() { return code; }
}
