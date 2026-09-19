package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * API contract + OpenAPI documentation for the Billing resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Billing")
public interface BillingApi {

    @Operation(summary = "List the public subscription plans")
    @ApiResponse(responseCode = "200", description = "Plans listed")
    @GetMapping("/plans")
    ResponseEntity<List<BillingPlanDtoOut>> listPlans();

    @Operation(summary = "Start a subscription checkout for the authenticated user")
    @ApiResponse(responseCode = "200", description = "Checkout started")
    @PostMapping("/subscribe")
    ResponseEntity<SubscribeDtoOut> subscribe(@AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody SubscribeDtoIn req) throws StripeException;
}
