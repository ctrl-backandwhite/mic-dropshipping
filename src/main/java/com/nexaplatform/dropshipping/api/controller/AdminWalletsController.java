package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWalletsApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletAdjustDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletTopupDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminWalletMapper;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin wallets controller. Pure implementation of {@link AdminWalletsApi}: injects
 * the use case + DtoMapper; no business logic and no manual field mapping — it only
 * paginates the models and projects them to the transport DtoOut.
 */
@RestController
@RequestMapping("/api/admin/wallets")
@RequiredArgsConstructor
public class AdminWalletsController implements AdminWalletsApi {

    private final WalletUseCase walletUseCase;
    private final AdminWalletMapper adminWalletMapper;

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> topup(UUID userId, AdminWalletTopupDtoIn req) {
        return ResponseEntity.ok(adminWalletMapper.toResult(
                walletUseCase.adminTopup(userId, req.getAmountCents(), req.getDescription(), req.getIdempotencyKey())));
    }

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> adjust(UUID userId, AdminWalletAdjustDtoIn req) {
        return ResponseEntity.ok(adminWalletMapper.toResult(
                walletUseCase.adminAdjustEntry(userId, req.getAmountCents(), req.getDescription(), req.getIdempotencyKey())));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletTxRowDtoOut>> transactions(UUID walletId, int page, int size) {
        int capped = Math.min(size, 100);
        List<AdminWalletTxRowDtoOut> items = adminWalletMapper.toTxRows(
                walletUseCase.adminTransactions(walletId, page, capped));
        long total = walletUseCase.countWalletTransactions(walletId);
        var pageable = PageRequest.of(page, capped);
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, total)));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletRowDtoOut>> list(String q, String status, String currency, int page, int size) {
        List<Wallet> all = walletUseCase.adminListWallets(q, status, currency);
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AdminWalletRowDtoOut> items = adminWalletMapper.toRows(all.subList(from, to));
        var pageable = PageRequest.of(page, Math.max(1, size));
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, total)));
    }
}
