package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Single recent-order row for the admin dashboard. Field names preserve the exact
 * JSON keys previously emitted by the controller's ad-hoc {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminDashboardRecentOrderDtoOut {

    UUID id;
    String orderNumber;
    String status;
    int totalCents;
    String currency;
    Instant placedAt;
}
