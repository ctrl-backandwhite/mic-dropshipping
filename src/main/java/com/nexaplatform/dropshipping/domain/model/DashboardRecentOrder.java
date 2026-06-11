package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection model for a single recent-order row of the admin dashboard.
 * Aggregated by the use case from the order entities; the api mapper translates it
 * to {@code AdminDashboardRecentOrderDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardRecentOrder {

    private UUID id;
    private String orderNumber;
    private String status;
    private int totalCents;
    private String currency;
    private Instant placedAt;
}
