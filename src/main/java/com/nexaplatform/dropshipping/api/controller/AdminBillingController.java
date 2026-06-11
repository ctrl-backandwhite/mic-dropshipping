package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminBillingApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import com.nexaplatform.dropshipping.application.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin billing controller. Pure implementation of {@link AdminBillingApi}:
 * no business logic and no manual mapping — delegates to {@link SubscriptionService}
 * and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController implements AdminBillingApi {

    private final SubscriptionService subscriptionService;

    @Override
    public ResponseEntity<List<AdminSubscriptionDtoOut>> subscriptions(String status) {
        return ResponseEntity.ok(subscriptionService.listAdminSubscriptions(status));
    }

    @Override
    public ResponseEntity<List<AdminPlanDtoOut>> plans() {
        return ResponseEntity.ok(subscriptionService.listAdminPlans());
    }

    @Override
    public ResponseEntity<OperationResponseDtoOut> updatePlan(String code, AdminPlanUpdateDtoIn body) {
        return ResponseEntity.ok(subscriptionService.updatePlan(code, body));
    }
}
