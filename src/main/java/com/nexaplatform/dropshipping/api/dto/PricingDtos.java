package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public final class PricingDtos {
    private PricingDtos() {
    }

    public record PriceRuleView(UUID id, String scope, UUID scopeId, String marginType, BigDecimal marginValue,
            BigDecimal minCostUsd, BigDecimal maxCostUsd, boolean active, int position, String description) {
    }

    public record PriceRuleRequest(
            @NotNull @Pattern(regexp = "^(GLOBAL|CATEGORY|SUPPLIER|PRODUCT|VARIANT)$") String scope, UUID scopeId,
            @NotNull @Pattern(regexp = "^(PERCENTAGE|FIXED)$") String marginType, @NotNull BigDecimal marginValue,
            BigDecimal minCostUsd, BigDecimal maxCostUsd, Boolean active, Integer position,
            // DROP-563: descripción ahora obligatoria — sin ella es imposible
            // auditar por qué se aplicó una regla concreta a un margen calculado.
            @NotBlank @Size(min = 3, max = 200) String description) {
    }
}
