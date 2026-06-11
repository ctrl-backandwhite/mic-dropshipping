package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.WalletDtos.WalletTxView;
import com.nexaplatform.dropshipping.api.dto.WalletDtos.WalletView;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminWalletTxRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminWalletMapper;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core money-handling service. All transactions are persisted in a single ledger
 * (`wallet_transaction`) for full audit trail. Idempotency-Key prevents double credit
 * when the same recharge or refund webhook is delivered twice.
 *
 * <p>Concurrency note: a SELECT FOR UPDATE on wallet rows would be ideal under high load;
 * for the dev workload this relies on optimistic JPA semantics + transactional boundaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final UserRepository userRepository;
    private final AuditLogger auditLogger;
    private final AdminWalletMapper adminWalletMapper;
    private final MeWalletDtoMapper meWalletDtoMapper;
    private final CurrencyRateService currencyService;

    /* ============ Read ============ */

    public WalletEntity getOrCreate(UUID userId) {
        return walletRepository.findByUser_Id(userId).orElseGet(() -> createFor(userId));
    }

    @Transactional
    protected WalletEntity createFor(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        WalletEntity w = WalletEntity.builder()
                .user(user)
                .balanceUsdCents(0L)
                .holdUsdCents(0L)
                .currencyDefault("USD")
                .status("ACTIVE")
                .build();
        return walletRepository.save(w);
    }

    public Page<WalletTransactionEntity> listTransactions(UUID walletId, Pageable pageable) {
        return txRepository.findByWallet_IdOrderByCreatedAtDesc(walletId, pageable);
    }

    /* ============================================================
     *  Me wallet use-cases (moved out of MeWalletController)
     * ============================================================ */

    /** Wallet snapshot for the authenticated user, including display-currency conversion. */
    @Transactional
    public WalletView getWalletView(UUID userId) {
        WalletEntity w = getOrCreate(userId);
        long available = Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents());
        String currency = CurrencyHolder.get();
        BigDecimal usd = BigDecimal.valueOf(w.getBalanceUsdCents()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal display = currencyService.usdTo(usd, currency);
        return new WalletView(w.getId(), w.getBalanceUsdCents(), w.getHoldUsdCents(), available,
                w.getCurrencyDefault(), w.getStatus(), display, currency, currencyService.symbolOf(currency));
    }

    /** Paginated wallet transactions for the authenticated user. */
    @Transactional(readOnly = true)
    public PageResponse<WalletTxView> getMyTransactions(UUID userId, int page, int size) {
        WalletEntity w = getOrCreate(userId);
        Page<WalletTransactionEntity> p = txRepository.findByWallet_IdOrderByCreatedAtDesc(
                w.getId(), PageRequest.of(page, Math.min(size, 100)));
        return PageResponse.map(p, t -> new WalletTxView(t.getId(), t.getKind(), t.getAmountUsdCents(),
                t.getBalanceAfterCents(), t.getStatus(), t.getDescription(),
                t.getPaymentId(), t.getOrderId(), t.getCreatedAt()));
    }

    /** Authenticated user's wallet projected to the transport DtoOut. */
    @Transactional
    public MeWalletDtoOut getWalletDtoOut(UUID userId) {
        return meWalletDtoMapper.toWalletDtoOut(getWalletView(userId));
    }

    /** Paginated wallet transactions for the authenticated user, projected to DtoOut. */
    @Transactional(readOnly = true)
    public PageResponse<MeWalletTxDtoOut> getMyTransactionDtos(UUID userId, int page, int size) {
        return PageResponse.map(
                txRepository.findByWallet_IdOrderByCreatedAtDesc(getOrCreate(userId).getId(),
                        PageRequest.of(page, Math.min(size, 100))),
                t -> meWalletDtoMapper.toTxDtoOut(new WalletTxView(t.getId(), t.getKind(), t.getAmountUsdCents(),
                        t.getBalanceAfterCents(), t.getStatus(), t.getDescription(),
                        t.getPaymentId(), t.getOrderId(), t.getCreatedAt())));
    }

    /* ============================================================
     *  Admin wallet use-cases (moved out of AdminWalletsController)
     * ============================================================ */

    /** Admin manual top-up. Settled as a deposit with a MANUAL_TOPUP description. */
    @Transactional
    public AdminWalletTxResultDtoOut adminTopup(UUID userId, long amountCents, String description, String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        String desc = description != null && !description.isBlank() ? description : "Admin manual top-up";
        WalletTransactionEntity tx = deposit(userId, amountCents, null, key, desc);
        return adminWalletMapper.toResult(tx);
    }

    /**
     * Admin manual adjustment. {@code amountCents} may be negative; the description
     * is mandatory and used as the reason (prefixed with {@code [Adjustment]}).
     */
    @Transactional
    public AdminWalletTxResultDtoOut adminAdjustEntry(UUID userId, long amountCents, String description, String idempotencyKey) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required for manual adjustments");
        }
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        WalletTransactionEntity tx = deposit(userId, amountCents, null, key, "[Adjustment] " + description);
        return adminWalletMapper.toResult(tx);
    }

    /** Admin paginated wallet transactions for a wallet, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AdminWalletTxRowDtoOut> adminTransactions(UUID walletId, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 100));
        Page<WalletTransactionEntity> p = txRepository.findByWallet_IdOrderByCreatedAtDesc(walletId, pageable);
        return PageResponse.map(p, adminWalletMapper::toTxRow);
    }

    /** Admin paginated wallet listing with status/currency/free-text filters. */
    @Transactional(readOnly = true)
    public PageResponse<AdminWalletRowDtoOut> adminListWallets(String q, String status, String currency, int page, int size) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<WalletEntity> all = walletRepository.findAll().stream()
                .filter(w -> status == null || status.isBlank() || w.getStatus().equalsIgnoreCase(status))
                .filter(w -> currency == null || currency.isBlank()
                        || (w.getCurrencyDefault() != null && w.getCurrencyDefault().equalsIgnoreCase(currency)))
                .filter(w -> needle.isEmpty()
                        || (w.getUser() != null && w.getUser().getEmail() != null
                            && w.getUser().getEmail().toLowerCase().contains(needle))
                        || (w.getUser() != null && w.getUser().getDisplayName() != null
                            && w.getUser().getDisplayName().toLowerCase().contains(needle)))
                .sorted((a, b) -> Long.compare(b.getBalanceUsdCents(), a.getBalanceUsdCents()))
                .toList();

        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AdminWalletRowDtoOut> items = all.subList(from, to).stream()
                .map(this::toAdminWalletRow).toList();
        var pageable = PageRequest.of(page, Math.max(1, size));
        return PageResponse.from(new PageImpl<>(items, pageable, total));
    }

    private AdminWalletRowDtoOut toAdminWalletRow(WalletEntity w) {
        return AdminWalletRowDtoOut.builder()
                .id(w.getId())
                .userId(w.getUser().getId())
                .email(w.getUser().getEmail())
                .name(w.getUser().getDisplayName())
                .balanceUsd(BigDecimal.valueOf(w.getBalanceUsdCents()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                .holdUsd(BigDecimal.valueOf(w.getHoldUsdCents()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                .currency(w.getCurrencyDefault() != null ? w.getCurrencyDefault() : "USD")
                .status(w.getStatus())
                .build();
    }

    /* ============ Mutations ============ */

    /** Credit funds (recharge from payment provider). */
    @Transactional
    public WalletTransactionEntity deposit(UUID userId, long amountUsdCents, UUID paymentId, String idempotencyKey, String description) {
        if (amountUsdCents <= 0) throw new BusinessException("Deposit amount must be positive");
        return record(userId, "DEPOSIT", amountUsdCents, paymentId, null, idempotencyKey, description, null);
    }

    /** Debit funds (from order checkout). */
    @Transactional
    public WalletTransactionEntity charge(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey, String description) {
        if (amountUsdCents <= 0) throw new BusinessException("Charge amount must be positive");
        WalletEntity w = require(userId);
        if (available(w) < amountUsdCents) {
            throw new BusinessException("Insufficient wallet balance");
        }
        return record(userId, "PAYMENT", -amountUsdCents, null, orderId, idempotencyKey, description, null);
    }

    /** Refund (credit back from a previous charge). */
    @Transactional
    public WalletTransactionEntity refund(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey, String reason) {
        return record(userId, "REFUND", amountUsdCents, null, orderId, idempotencyKey, reason, null);
    }

    /** Admin adjustment (positive credit or negative debit). */
    @Transactional
    public WalletTransactionEntity adminAdjust(UUID userId, long signedAmountCents, String idempotencyKey, String reason) {
        WalletTransactionEntity tx = record(userId, "ADJUSTMENT", signedAmountCents, null, null, idempotencyKey, reason, null);
        auditLogger.log("wallet.admin_adjust", String.valueOf(userId), Map.of("amount", signedAmountCents, "reason", reason));
        return tx;
    }

    /** Hold funds (e.g. for checkout pending payment confirmation). */
    @Transactional
    public WalletTransactionEntity hold(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        WalletEntity w = require(userId);
        if (available(w) < amountUsdCents) throw new BusinessException("Insufficient balance to hold");
        w.setHoldUsdCents(w.getHoldUsdCents() + amountUsdCents);
        walletRepository.save(w);
        return record(userId, "HOLD", -amountUsdCents, null, orderId, idempotencyKey, "Hold for order", w);
    }

    @Transactional
    public WalletTransactionEntity release(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        WalletEntity w = require(userId);
        w.setHoldUsdCents(Math.max(0L, w.getHoldUsdCents() - amountUsdCents));
        walletRepository.save(w);
        return record(userId, "RELEASE", amountUsdCents, null, orderId, idempotencyKey, "Release hold", w);
    }

    /* ============ Internals ============ */

    private long available(WalletEntity w) { return Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents()); }

    private WalletEntity require(UUID userId) {
        return walletRepository.findByUser_Id(userId).orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    private WalletTransactionEntity record(UUID userId, String kind, long signedAmount,
                                           UUID paymentId, UUID orderId,
                                           String idempotencyKey, String description,
                                           WalletEntity preloadedWallet) {
        if (idempotencyKey != null) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Returning existing wallet tx for idempotency-key={}", idempotencyKey);
                return existing.get();
            }
        }
        WalletEntity w = preloadedWallet != null ? preloadedWallet : require(userId);
        // For HOLD/RELEASE we do NOT change balance, only hold counter (signedAmount is informational).
        boolean affectsBalance = !"HOLD".equals(kind) && !"RELEASE".equals(kind);
        long newBalance = w.getBalanceUsdCents();
        if (affectsBalance) {
            newBalance += signedAmount;
            if (newBalance < 0) throw new BusinessException("Wallet balance would go negative");
            w.setBalanceUsdCents(newBalance);
            walletRepository.save(w);
        }
        WalletTransactionEntity tx = WalletTransactionEntity.builder()
                .wallet(w)
                .kind(kind)
                .amountUsdCents(signedAmount)
                .balanceAfterCents(newBalance)
                .paymentId(paymentId)
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .status("COMPLETED")
                .description(description)
                .build();
        WalletTransactionEntity saved = txRepository.save(tx);
        auditLogger.log("wallet." + kind.toLowerCase(), String.valueOf(userId),
                Map.of("amount", signedAmount, "balance_after", newBalance, "tx_id", saved.getId()));
        return saved;
    }
}
