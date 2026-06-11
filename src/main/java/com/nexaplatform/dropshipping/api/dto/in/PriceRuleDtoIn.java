package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload to create/update a price rule. Mirrors the legacy
 * {@code PriceRuleRequest} record (same validation constraints) so the
 * frontend contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceRuleDtoIn {

    @NotNull
    @Pattern(regexp = "^(GLOBAL|CATEGORY|SUPPLIER|PRODUCT|VARIANT)$")
    @Schema(description = "Rule scope", example = "GLOBAL")
    private String scope;

    @Schema(description = "Identifier of the scoped entity (null for GLOBAL)")
    private UUID scopeId;

    @NotNull
    @Pattern(regexp = "^(PERCENTAGE|FIXED)$")
    @Schema(description = "Margin type", example = "PERCENTAGE")
    private String marginType;

    @NotNull
    @Schema(description = "Margin value")
    private BigDecimal marginValue;

    @Schema(description = "Minimum cost (USD) for the rule to apply")
    private BigDecimal minCostUsd;

    @Schema(description = "Maximum cost (USD) for the rule to apply")
    private BigDecimal maxCostUsd;

    @Schema(description = "Whether the rule is active (defaults to true)")
    private Boolean active;

    @Schema(description = "Resolution priority (defaults to 0)")
    private Integer position;

    // DROP-563: description is mandatory so every applied margin can be audited.
    @NotBlank
    @Size(min = 3, max = 200)
    @Schema(description = "Human-readable description")
    private String description;
}
