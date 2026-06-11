package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Result of a wallet payment confirmation (PayPal capture / dev mock-confirm).
 * Field names mirror the keys the controller previously placed into its
 * {@code Map.of(...)} response; {@code balanceUsdCents} is only populated by the
 * mock-confirm endpoint.
 */
@Value
@Builder
public class MeWalletPaymentStatusDtoOut {

    UUID paymentId;
    String status;
    Long balanceUsdCents;
}
