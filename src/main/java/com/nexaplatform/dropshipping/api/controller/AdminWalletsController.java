package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWalletsApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletAdjustDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletTopupDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.application.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin wallets controller. Pure implementation of {@link AdminWalletsApi}:
 * no business logic and no manual mapping — delegates to {@link WalletService}
 * and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/wallets")
@RequiredArgsConstructor
public class AdminWalletsController implements AdminWalletsApi {

    private final WalletService walletService;

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> topup(UUID userId, AdminWalletTopupDtoIn req) {
        return ResponseEntity.ok(walletService.adminTopup(userId, req.getAmountCents(), req.getDescription(), req.getIdempotencyKey()));
    }

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> adjust(UUID userId, AdminWalletAdjustDtoIn req) {
        return ResponseEntity.ok(walletService.adminAdjustEntry(userId, req.getAmountCents(), req.getDescription(), req.getIdempotencyKey()));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletTxRowDtoOut>> transactions(UUID walletId, int page, int size) {
        return ResponseEntity.ok(walletService.adminTransactions(walletId, page, size));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletRowDtoOut>> list(String q, String status, String currency, int page, int size) {
        return ResponseEntity.ok(walletService.adminListWallets(q, status, currency, page, size));
    }
}
