package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.DashboardMetrics;
import com.nexaplatform.dropshipping.domain.model.DashboardRecentOrder;
import com.nexaplatform.dropshipping.domain.model.DashboardSeries;

import java.util.List;

/**
 * Use-case port for the admin dashboard read projections. Each method returns a
 * domain projection model (not a DtoOut); the api mapper translates to the
 * transport DTOs. Pure reads aggregating several repositories — no domain port.
 */
public interface AdminDashboardUseCase {

    /** KPI metrics aggregated across products, orders, suppliers, users and subscriptions. */
    DashboardMetrics metrics();

    /** Order/GMV time-series buckets for the last 30 days. */
    DashboardSeries series();

    /** The most recent orders (capped), sorted by placement date descending. */
    List<DashboardRecentOrder> recentOrders();
}
