package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * A wallet transaction row in the admin transactions listing. Field names mirror
 * the keys the controller previously placed into its per-row {@code Map}.
 */
@Value
@Builder
public class AdminWalletTxRowDtoOut {

    UUID id;
    String kind;
    long amountCents;
    long balanceAfterCents;
    String description;
    String status;
    Instant createdAt;
}
