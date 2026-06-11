package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Output view of a subscription plan, including audit metadata. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlanDtoOut {

    @Schema(description = "Plan id")
    private UUID id;

    @Schema(description = "Unique plan code", example = "pro")
    private String code;

    @Schema(description = "Display name", example = "Pro")
    private String name;

    @Schema(description = "Plan description")
    private String description;

    @Schema(description = "Monthly price in cents", example = "4900")
    private int priceMonthlyCents;

    @Schema(description = "Yearly price in cents", example = "49000")
    private int priceYearlyCents;

    @Schema(description = "ISO currency code", example = "USD")
    private String currency;

    @Schema(description = "Whether the plan is active", example = "true")
    private boolean active;

    @Schema(description = "Sort position", example = "1")
    private int position;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Last update timestamp")
    private Instant updatedAt;

    @Schema(description = "User who created the record")
    private String createdBy;

    @Schema(description = "User who last updated the record")
    private String updatedBy;
}
