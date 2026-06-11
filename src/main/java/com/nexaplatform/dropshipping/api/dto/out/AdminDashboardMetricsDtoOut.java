package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Aggregated KPI metrics for the admin dashboard. Field names preserve the exact
 * JSON keys previously emitted by the controller's ad-hoc {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminDashboardMetricsDtoOut {

    long activeProducts;
    long totalProducts;
    long draftProducts;
    long totalOrders;
    long totalUsers;
    long totalSuppliers;
    long activePlans;
    long totalSubscriptions;
    BigDecimal gmvUsd;
    BigDecimal gmvDisplay;
    BigDecimal mrrUsd;
    String displayCurrency;
    String displaySymbol;
}
