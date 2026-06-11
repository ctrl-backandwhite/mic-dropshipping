package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin manual top-up payload. Settled as a MANUAL_TOPUP wallet transaction.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletTopupDtoIn {

    @NotNull
    @Positive
    private Long amountCents;

    private String description;

    private String idempotencyKey;
}
