package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the authenticated user's Order Payments resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Me · Order Payments", description = "Pay your own dropship order from the customer UI")
@SecurityRequirement(name = "session")
public interface MeOrderPaymentApi {

    @Operation(summary = "Initiate payment for the authenticated user's order")
    @PostMapping("/{orderId}/payment-intent")
    ResponseEntity<OrderPaymentDtoOut> initiate(
            Authentication auth,
            @PathVariable UUID orderId,
            @Valid @RequestBody OrderPaymentIntentDtoIn req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey);

    @Operation(summary = "Dev-only: mock-confirm a pending order payment (no real provider call)")
    @PostMapping("/{orderId}/payments/{paymentId}/confirm-mock")
    ResponseEntity<OrderPaymentDtoOut> confirmMock(
            Authentication auth,
            @PathVariable UUID orderId,
            @PathVariable UUID paymentId);
}
