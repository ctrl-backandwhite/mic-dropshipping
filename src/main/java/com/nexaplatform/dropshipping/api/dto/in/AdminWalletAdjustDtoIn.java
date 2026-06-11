package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Admin manual adjustment payload. {@code amountCents} may be negative; the
 * description is mandatory (validated in the service) and used as the reason.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminWalletAdjustDtoIn {

    @NotNull
    private Long amountCents;

    private String description;

    private String idempotencyKey;
}
