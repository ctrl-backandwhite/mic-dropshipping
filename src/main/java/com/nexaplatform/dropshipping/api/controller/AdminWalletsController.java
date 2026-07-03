package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWalletsApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletAdjustDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminWalletTopupDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminWalletMapper;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
    private final WalletIndexer walletIndexer;

    /** Reindexa todas las wallets en OpenSearch (botón "Reindexar" del admin). */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", walletIndexer.reindexAll()));
    }

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> topup(UUID userId, AdminWalletTopupDtoIn req) {
        return ResponseEntity.ok(adminWalletMapper.toResult(
                walletUseCase.adminTopup(userId, req.getAmountCents(), req.getDescription(), req.getIdempotencyKey())));
    }

    @Override
    public ResponseEntity<AdminWalletTxResultDtoOut> adjust(UUID userId, AdminWalletAdjustDtoIn req) {
        return ResponseEntity.ok(adminWalletMapper.toResult(walletUseCase.adminAdjustEntry(userId, req.getAmountCents(),
                req.getDescription(), req.getIdempotencyKey())));
    }

    @Override
    public ResponseEntity<AdminWalletDetailDtoOut> detail(UUID userId) {
        return ResponseEntity.ok(adminWalletMapper.toDetail(walletUseCase.adminGetWalletDetail(userId)));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletTxRowDtoOut>> transactions(UUID walletId, int page, int size) {
        int capped = Math.min(size, 100);
        List<AdminWalletTxRowDtoOut> items = adminWalletMapper
                .toTxRows(walletUseCase.adminTransactions(walletId, page, capped));
        long total = walletUseCase.countWalletTransactions(walletId);
        var pageable = PageRequest.of(page, capped);
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, total)));
    }

    @Override
    public ResponseEntity<PageResponse<AdminWalletRowDtoOut>> list(String q, String status, String currency, int page,
            int size) {
        WalletUseCase.WalletPage p = walletUseCase.pageAdminWallets(q, status, currency, page, size);
        List<AdminWalletRowDtoOut> items = adminWalletMapper.toRows(p.items());
        var pageable = PageRequest.of(Math.max(0, p.page()), Math.max(1, p.size()));
        return ResponseEntity.ok(PageResponse.from(new PageImpl<>(items, pageable, p.total())));
    }
}
