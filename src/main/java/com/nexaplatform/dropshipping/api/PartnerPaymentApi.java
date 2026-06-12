package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the partner payments resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Partner · Payments", description = "Pay a dropship order with wallet, card, PayPal or USDT")
@SecurityRequirement(name = "oauth2")
@SecurityRequirement(name = "bearer-jwt")
public interface PartnerPaymentApi {

    @Operation(summary = "Initiate payment for an order", description = """
            Creates a Payment record for the given order and method.
              - **WALLET** → debits the partner's NX036 wallet atomically; order moves to PAID immediately.
              - **CARD**   → returns `clientSecret` (Stripe Elements). The partner's frontend confirms.
              - **PAYPAL** → returns `approveUrl` (redirect the merchant to PayPal).
              - **USDT**   → returns `cryptoAddress`, `cryptoChain`, `qrUrl`, `cryptoExpiresAt` (30 min TTL).

            `Idempotency-Key` is honored — the same key returns the same Payment without re-charging.
            """, requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = OrderPaymentIntentDtoIn.class), examples = {
            @ExampleObject(name = "WALLET", value = "{\"method\":\"WALLET\"}"),
            @ExampleObject(name = "CARD", value = "{\"method\":\"CARD\"}"),
            @ExampleObject(name = "PAYPAL", value = "{\"method\":\"PAYPAL\"}"),
            @ExampleObject(name = "USDT", value = "{\"method\":\"USDT\"}"),})))
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Payment created (or returned from idempotency)"),
            @ApiResponse(responseCode = "400", description = "Order in non-payable state, or wallet without sufficient balance"),
            @ApiResponse(responseCode = "404", description = "Order not found")})
    @PostMapping("/{orderId}/payment-intent")
    ResponseEntity<OrderPaymentDtoOut> initiate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
            @Valid @RequestBody OrderPaymentIntentDtoIn req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey);

    @Operation(summary = "List all payment attempts associated to an order")
    @GetMapping("/{orderId}/payments")
    ResponseEntity<List<OrderPaymentDtoOut>> list(@PathVariable UUID orderId);

    @Operation(summary = "Read a payment by id (poll while pending)")
    @GetMapping("/{orderId}/payments/{paymentId}")
    ResponseEntity<OrderPaymentDtoOut> get(@PathVariable UUID orderId, @PathVariable UUID paymentId);
}
