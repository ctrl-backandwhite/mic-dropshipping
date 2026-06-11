package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body to start a subscription checkout for the authenticated user.
 * Replaces the legacy {@code BillingDtos.SubscribeRequest} record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeDtoIn {

    @Schema(description = "Target plan code", example = "PRO")
    @NotBlank
    private String planCode;

    @Schema(description = "Billing period: MONTHLY or YEARLY", example = "MONTHLY")
    @NotBlank
    private String period;
}
