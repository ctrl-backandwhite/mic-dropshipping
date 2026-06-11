package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Result of an admin manual top-up or adjustment. Field names mirror the keys
 * the controller previously placed into its {@code Map.of(...)} response.
 */
@Value
@Builder
public class AdminWalletTxResultDtoOut {

    UUID transactionId;
    long balanceAfter;
    long amountCents;
}
