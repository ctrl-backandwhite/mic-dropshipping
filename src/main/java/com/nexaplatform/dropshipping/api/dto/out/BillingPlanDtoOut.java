package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Public subscription plan projection exposed by the storefront billing API.
 * Replaces the legacy {@code BillingDtos.PlanView} record; field names are kept
 * identical so the frontend contract is preserved.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanDtoOut {

    @Schema(description = "Plan id")
    private UUID id;

    @Schema(description = "Plan code")
    private String code;

    @Schema(description = "Plan display name")
    private String name;

    @Schema(description = "Plan description")
    private String description;

    @Schema(description = "Monthly price in cents")
    private int priceMonthlyCents;

    @Schema(description = "Yearly price in cents")
    private int priceYearlyCents;

    @Schema(description = "ISO currency code")
    private String currency;

    @Schema(description = "Ordering position")
    private int position;

    @Schema(description = "Feature limits keyed by feature key")
    private Map<String, Long> limits;
}
