package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminDashboardApi;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardMetricsDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import com.nexaplatform.dropshipping.application.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin Dashboard controller. Pure implementation of {@link AdminDashboardApi}:
 * no business logic and no manual mapping — delegates to
 * {@link AdminDashboardService} and wraps every result in a standardized
 * {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController implements AdminDashboardApi {

    private final AdminDashboardService adminDashboardService;

    @Override
    public ResponseEntity<AdminDashboardMetricsDtoOut> metrics() {
        return new ResponseEntity<>(adminDashboardService.metrics(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminDashboardSeriesDtoOut> series() {
        return new ResponseEntity<>(adminDashboardService.series(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminDashboardRecentOrderDtoOut>> recentOrders() {
        return new ResponseEntity<>(adminDashboardService.recentOrders(), HttpStatus.OK);
    }
}
