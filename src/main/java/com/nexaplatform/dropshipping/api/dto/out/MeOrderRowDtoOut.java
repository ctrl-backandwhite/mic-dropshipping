package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact row for the authenticated user's order list. Field names mirror the
 * keys the controller previously placed into its per-row {@code Map}.
 */
@Value
@Builder
public class MeOrderRowDtoOut {

    UUID id;
    String orderNumber;
    String status;
    int totalCents;
    String currency;
    int itemCount;
    Instant placedAt;
    Instant shippedAt;
    Instant deliveredAt;
}
