package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transport representation of a price rule returned by the Admin Pricing API.
 * Field names mirror the legacy {@code PriceRuleView} record so the frontend
 * contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceRuleDtoOut {

    @Schema(description = "Rule identifier")
    private UUID id;

    @Schema(description = "Rule scope", example = "GLOBAL")
    private String scope;

    @Schema(description = "Identifier of the scoped entity (null for GLOBAL)")
    private UUID scopeId;

    @Schema(description = "Human-readable name of the scoped entity (category/supplier/product/variant); null for GLOBAL")
    private String scopeName;

    @Schema(description = "Margin type", example = "PERCENTAGE")
    private String marginType;

    @Schema(description = "Margin value")
    private BigDecimal marginValue;

    @Schema(description = "Minimum cost (USD) for the rule to apply")
    private BigDecimal minCostUsd;

    @Schema(description = "Maximum cost (USD) for the rule to apply")
    private BigDecimal maxCostUsd;

    @Schema(description = "Whether the rule is active")
    private boolean active;

    @Schema(description = "Resolution priority")
    private int position;

    @Schema(description = "Human-readable description")
    private String description;

    @Schema(description = "Canal: STOREFRONT (tienda propia) o INTEGRATION (apps API: Shopify/WooCommerce)", example = "STOREFRONT")
    private String channel;

    @Schema(description = "País de destino (ISO-2) al que aplica; vacío = cualquier país", example = "DE")
    private String countryCode;
}
