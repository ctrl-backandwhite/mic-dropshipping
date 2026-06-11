package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminDashboardApi;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardMetricsDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminDashboardMapper;
import com.nexaplatform.dropshipping.application.usecase.AdminDashboardUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin Dashboard controller. Pure implementation of {@link AdminDashboardApi}:
 * no business logic and no manual mapping — delegates to
 * {@link AdminDashboardUseCase}, maps the domain projection models to DtoOuts via
 * {@link AdminDashboardMapper} and wraps every result in a standardized
 * {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController implements AdminDashboardApi {

    private final AdminDashboardMapper mapper;
    private final AdminDashboardUseCase useCase;

    @Override
    public ResponseEntity<AdminDashboardMetricsDtoOut> metrics() {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.metrics()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminDashboardSeriesDtoOut> series() {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.series()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<AdminDashboardRecentOrderDtoOut>> recentOrders() {
        return new ResponseEntity<>(mapper.toRecentOrderDtoList(useCase.recentOrders()), HttpStatus.OK);
    }
}
