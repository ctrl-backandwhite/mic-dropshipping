package com.nexaplatform.dropshipping.api.dto.in;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shipping-calculator / carbon-footprint request. Accepts both the legacy
 * ({@code weightGrams}, {@code qty}) and newer ({@code quantity}) field names
 * to preserve backward compatibility.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShippingCalculatorDtoIn {

    private Integer weightGrams;
    private Integer qty;
    private String country;
    private String productSlug;
    private String destinationCountry;
    private Integer quantity;
    private String transportMode;

    /** Effective per-unit weight in grams (defaults to 500). */
    public int effectiveWeight() {
        return weightGrams == null ? 500 : weightGrams;
    }

    /** Effective quantity, preferring {@code quantity}, then {@code qty}, min 1. */
    public int effectiveQty() {
        // El cliente puede mandar el campo con cualquiera de los dos nombres; manda el explícito.
        if (quantity != null) {
            return Math.max(1, quantity);
        }
        return qty != null ? Math.max(1, qty) : 1;
    }
}
