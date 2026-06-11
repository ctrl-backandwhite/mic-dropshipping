package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardMetricsDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the Admin Dashboard resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Dashboard")
public interface AdminDashboardApi {

    @Operation(summary = "Get admin dashboard metrics")
    @ApiResponse(responseCode = "200", description = "Metrics returned")
    @GetMapping("/metrics")
    ResponseEntity<AdminDashboardMetricsDtoOut> metrics();

    @Operation(summary = "Get admin dashboard time series")
    @ApiResponse(responseCode = "200", description = "Series returned")
    @GetMapping("/series")
    ResponseEntity<AdminDashboardSeriesDtoOut> series();

    @Operation(summary = "Get admin dashboard recent orders")
    @ApiResponse(responseCode = "200", description = "Recent orders returned")
    @GetMapping("/recent-orders")
    ResponseEntity<List<AdminDashboardRecentOrderDtoOut>> recentOrders();
}
