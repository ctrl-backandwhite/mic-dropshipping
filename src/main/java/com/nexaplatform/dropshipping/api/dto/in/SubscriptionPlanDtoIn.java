package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to create/update a subscription plan. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlanDtoIn {

    @NotBlank
    @Size(max = 40)
    @Schema(description = "Unique plan code", example = "pro")
    private String code;

    @NotBlank
    @Size(max = 120)
    @Schema(description = "Display name", example = "Pro")
    private String name;

    @Size(max = 1000)
    @Schema(description = "Plan description")
    private String description;

    @PositiveOrZero
    @Schema(description = "Monthly price in cents", example = "4900")
    private int priceMonthlyCents;

    @PositiveOrZero
    @Schema(description = "Yearly price in cents", example = "49000")
    private int priceYearlyCents;

    @Size(max = 8)
    @Schema(description = "ISO currency code", example = "USD")
    private String currency;

    @Schema(description = "Whether the plan is active", example = "true")
    private boolean active;

    @PositiveOrZero
    @Schema(description = "Sort position", example = "1")
    private int position;
}
