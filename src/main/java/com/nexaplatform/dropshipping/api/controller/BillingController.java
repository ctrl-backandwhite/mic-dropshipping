package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.BillingApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.nexaplatform.dropshipping.api.mapper.BillingDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Storefront billing controller. Pure implementation of {@link BillingApi}:
 * injects the {@link BillingDtoMapper} + {@link CustomerSubscriptionUseCase};
 * maps the use-case results to DtoOut; no business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/storefront/billing")
@RequiredArgsConstructor
public class BillingController implements BillingApi {

    private final BillingDtoMapper mapper;
    private final CustomerSubscriptionUseCase useCase;

    @Override
    public ResponseEntity<List<BillingPlanDtoOut>> listPlans() {
        return ResponseEntity.ok(mapper.toPlanDtoOutList(useCase.listPublicPlans()));
    }

    @Override
    public ResponseEntity<SubscribeDtoOut> subscribe(UserDetails principal, SubscribeDtoIn req) throws Exception {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(mapper.toSubscribeDtoOut(useCase.subscribe(userId, req.getPlanCode(), req.getPeriod())));
    }
}
