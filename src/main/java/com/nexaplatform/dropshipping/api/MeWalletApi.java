package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.application.usecase.RechargeOptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the authenticated user's Wallet resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Wallet")
public interface MeWalletApi {

    @Operation(summary = "Get the authenticated user's wallet")
    @ApiResponse(responseCode = "200", description = "Wallet returned")
    @GetMapping
    ResponseEntity<MeWalletDtoOut> wallet(Authentication auth);

    @Operation(summary = "List the authenticated user's wallet transactions")
    @ApiResponse(responseCode = "200", description = "Transactions listed")
    @GetMapping("/transactions")
    ResponseEntity<PageResponse<MeWalletTxDtoOut>> transactions(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "Recharge the authenticated user's wallet")
    @ApiResponse(responseCode = "200", description = "Recharge initiated")
    @PostMapping("/recharge")
    ResponseEntity<MeWalletRechargeDtoOut> recharge(Authentication auth, @Valid @RequestBody MeWalletRechargeDtoIn req,
            @Parameter(description = "Clave del INTENTO, no de la petición: la misma en los reintentos del mismo gesto. Sin ella, dos peticiones son dos cobros.", required = true) @RequestHeader(value = "Idempotency-Key") String idem);

    @Operation(summary = "Rounded recharge presets in the active currency (backend-computed)")
    @ApiResponse(responseCode = "200", description = "Recharge options returned")
    @GetMapping("/recharge/options")
    ResponseEntity<RechargeOptions> rechargeOptions(@RequestParam(value = "currency", required = false) String currency);

    @Operation(summary = "Confirm a wallet recharge on return from the provider (Stripe/PayPal) and credit it")
    @ApiResponse(responseCode = "200", description = "Recharge confirmed and credited")
    @PostMapping("/recharge/{paymentId}/confirm")
    ResponseEntity<MeWalletPaymentStatusDtoOut> confirmRecharge(Authentication auth,
            @PathVariable("paymentId") UUID paymentId);

    @Operation(summary = "Capture a PayPal wallet recharge result")
    @ApiResponse(responseCode = "200", description = "PayPal capture processed")
    @PostMapping("/paypal/capture")
    ResponseEntity<MeWalletPaymentStatusDtoOut> capturePayPal(Authentication auth,
            @RequestParam("paymentId") UUID paymentId);

    @Operation(summary = "Dev-only: mock-confirm a pending wallet recharge")
    @ApiResponse(responseCode = "200", description = "Recharge mock-confirmed")
    @PostMapping("/confirm-mock")
    ResponseEntity<MeWalletPaymentStatusDtoOut> confirmMock(Authentication auth,
            @RequestParam("paymentId") UUID paymentId);
}
