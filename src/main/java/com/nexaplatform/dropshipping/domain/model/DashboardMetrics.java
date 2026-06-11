package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/**
 * Read-only projection model for the admin dashboard KPI metrics. Aggregated by
 * the use case from several repositories; the api mapper translates it to
 * {@code AdminDashboardMetricsDtoOut}. Not a persisted aggregate.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetrics {

    private long activeProducts;
    private long totalProducts;
    private long draftProducts;
    private long totalOrders;
    private long totalUsers;
    private long totalSuppliers;
    private long activePlans;
    private long totalSubscriptions;
    private BigDecimal gmvUsd;
    private BigDecimal gmvDisplay;
    private BigDecimal mrrUsd;
    private String displayCurrency;
    private String displaySymbol;
}
