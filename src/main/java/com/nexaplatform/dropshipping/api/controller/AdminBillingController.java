package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminBillingApi;
import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminBillingMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin billing controller. Pure implementation of {@link AdminBillingApi}:
 * injects the {@link AdminBillingMapper} + {@link CustomerSubscriptionUseCase};
 * maps domain models to DtoOut (and the update DtoIn to a partial plan model);
 * no business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController implements AdminBillingApi {

    private final AdminBillingMapper mapper;
    private final CustomerSubscriptionUseCase useCase;

    @Override
    public ResponseEntity<List<AdminSubscriptionDtoOut>> subscriptions(String status) {
        return ResponseEntity.ok(mapper.toSubscriptionDtos(useCase.listAdminSubscriptions(status)));
    }

    @Override
    public ResponseEntity<List<AdminPlanDtoOut>> plans() {
        return ResponseEntity.ok(mapper.toPlanDtos(useCase.listAdminPlans()));
    }

    @Override
    public ResponseEntity<OperationResponseDtoOut> updatePlan(String code, AdminPlanUpdateDtoIn body) {
        useCase.updatePlan(code, mapper.toPlanChanges(body));
        return ResponseEntity.ok(OperationResponseDtoOut.ok("Plan updated"));
    }
}
