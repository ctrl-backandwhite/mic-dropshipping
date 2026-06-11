package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.UUID;

/** Output view of a user's affiliate account. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffiliateDtoOut {

    @Schema(description = "Affiliate id")
    private UUID id;

    @Schema(description = "Affiliate code")
    private String code;

    @Schema(description = "Total earnings in USD cents")
    private long earningsUsdCents;

    @Schema(description = "Total payout in USD cents")
    private long payoutUsdCents;

    @Schema(description = "Number of referrals")
    private int referralsCount;

    @Schema(description = "Whether the affiliate account is active")
    private boolean active;
}
