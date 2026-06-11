package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletAdjustDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletTopupDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Wallets resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Wallets")
public interface AdminWalletsApi {

    @Operation(summary = "Top up a user's wallet")
    @ApiResponse(responseCode = "200", description = "Wallet topped up")
    @PostMapping("/{userId}/topup")
    ResponseEntity<AdminWalletTxResultDtoOut> topup(@PathVariable UUID userId, @Valid @RequestBody AdminWalletTopupDtoIn req);

    @Operation(summary = "Adjust a user's wallet balance")
    @ApiResponse(responseCode = "200", description = "Wallet adjusted")
    @PostMapping("/{userId}/adjust")
    ResponseEntity<AdminWalletTxResultDtoOut> adjust(@PathVariable UUID userId, @Valid @RequestBody AdminWalletAdjustDtoIn req);

    @Operation(summary = "List wallet transactions with pagination")
    @ApiResponse(responseCode = "200", description = "Transactions listed")
    @GetMapping("/{walletId}/transactions")
    ResponseEntity<PageResponse<AdminWalletTxRowDtoOut>> transactions(@PathVariable UUID walletId,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "30") int size);

    @Operation(summary = "List wallets with optional filters and pagination")
    @ApiResponse(responseCode = "200", description = "Wallets listed")
    @GetMapping
    ResponseEntity<PageResponse<AdminWalletRowDtoOut>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currency,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size);
}
