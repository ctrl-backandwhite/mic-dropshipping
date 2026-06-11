package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.BillingApi;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.nexaplatform.dropshipping.application.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Storefront billing controller. Pure implementation of {@link BillingApi}:
 * no business logic and no manual mapping — delegates to {@link SubscriptionService}
 * and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/storefront/billing")
@RequiredArgsConstructor
public class BillingController implements BillingApi {

    private final SubscriptionService subscriptionService;

    @Override
    public ResponseEntity<List<BillingPlanDtoOut>> listPlans() {
        return ResponseEntity.ok(subscriptionService.listPublicPlanDtos());
    }

    @Override
    public ResponseEntity<SubscribeDtoOut> subscribe(UserDetails principal, SubscribeDtoIn req) throws Exception {
        UUID userId = UUID.fromString(principal.getUsername());
        return ResponseEntity.ok(subscriptionService.subscribe(userId, req));
    }
}
