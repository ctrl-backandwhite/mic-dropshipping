package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "Recharge the authenticated user's wallet")
    @ApiResponse(responseCode = "200", description = "Recharge initiated")
    @PostMapping("/recharge")
    ResponseEntity<MeWalletRechargeDtoOut> recharge(Authentication auth,
                                                    @Valid @RequestBody MeWalletRechargeDtoIn req,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem);

    @Operation(summary = "Capture a PayPal wallet recharge result")
    @ApiResponse(responseCode = "200", description = "PayPal capture processed")
    @PostMapping("/paypal/capture")
    ResponseEntity<MeWalletPaymentStatusDtoOut> capturePayPal(@RequestParam("paymentId") UUID paymentId);

    @Operation(summary = "Dev-only: mock-confirm a pending wallet recharge")
    @ApiResponse(responseCode = "200", description = "Recharge mock-confirmed")
    @PostMapping("/confirm-mock")
    ResponseEntity<MeWalletPaymentStatusDtoOut> confirmMock(Authentication auth, @RequestParam("paymentId") UUID paymentId);
}
