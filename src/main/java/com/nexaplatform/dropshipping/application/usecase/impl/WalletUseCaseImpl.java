package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.WalletSearchService;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Core money-handling use case. All transactions are persisted in a single ledger
 * ({@code wallet_transaction}) for a full audit trail. Idempotency-Key prevents
 * double credit when the same recharge or refund webhook is delivered twice.
 *
 * <p>Operates on the {@link Wallet}/{@link WalletTransaction} domain models and
 * delegates persistence to the domain ports. Logic moved verbatim out of the
 * legacy {@code WalletService}, preserving the money semantics exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletUseCaseImpl implements WalletUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository txRepository;
    private final AuditLogger auditLogger;
    private final CurrencyRateService currencyService;
    private final WalletIndexer walletIndexer;
    private final WalletSearchService walletSearchService;

    /* ============ Read ============ */

    @Override
    @Transactional
    public Wallet getOrCreate(UUID userId) {
        return walletRepository.findByUserId(userId).orElseGet(() -> createFor(userId));
    }

    private Wallet createFor(UUID userId) {
        Wallet w = Wallet.builder().userId(userId).balanceUsdCents(0L).holdUsdCents(0L).currencyDefault("USD")
                .status("ACTIVE").build();
        Wallet saved = walletRepository.save(w);
        walletIndexer.indexWallet(saved); // auto-sync del índice al crear la wallet
        return saved;
    }

    @Override
    @Transactional
    public Wallet getMyWallet(UUID userId) {
        Wallet w = getOrCreate(userId);
        long available = Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents());
        String currency = CurrencyHolder.get();
        BigDecimal usd = BigDecimal.valueOf(w.getBalanceUsdCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        BigDecimal display = currencyService.usdTo(usd, currency);
        w.setAvailableUsdCents(available);
        w.setBalanceDisplay(display);
        w.setDisplayCurrency(currency);
        w.setDisplaySymbol(currencyService.symbolOf(currency));
        // Formateo EN EL BACKEND con la convención del país del visor (locale de la divisa activa): tanto el
        // saldo en divisa como el canónico en USD llevan los mismos separadores (coma decimal/punto de miles
        // en español), igual que Stripe. El front solo pinta estos strings.
        String vLocale = currencyService.localeOf(currency);
        BigDecimal holdUsd = BigDecimal.valueOf(w.getHoldUsdCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        w.setBalanceFormatted(currencyService.formatIn(display, currency, vLocale));
        w.setBalanceUsdFormatted(currencyService.formatIn(usd, "USD", vLocale));
        w.setHoldUsdFormatted(currencyService.formatIn(holdUsd, "USD", vLocale));
        return w;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransaction> getMyTransactions(UUID userId, int page, int size) {
        Wallet w = getOrCreate(userId);
        List<WalletTransaction> txs = txRepository.findByWalletIdOrderByCreatedAtDesc(w.getId(), page,
                Math.min(size, 100));
        // Importes del libro mayor (USD canónico) formateados EN EL BACKEND con la convención del país del
        // visor (locale de la divisa activa) y con signo. El front solo pinta.
        String vLocale = currencyService.localeOf(CurrencyHolder.get());
        for (WalletTransaction t : txs) {
            BigDecimal amt = BigDecimal.valueOf(t.getAmountUsdCents()).divide(BigDecimal.valueOf(100), 2,
                    RoundingMode.HALF_UP);
            BigDecimal after = BigDecimal.valueOf(t.getBalanceAfterCents()).divide(BigDecimal.valueOf(100), 2,
                    RoundingMode.HALF_UP);
            String sign = t.getAmountUsdCents() >= 0 ? "+" : "-";
            t.setAmountFormatted(sign + currencyService.formatIn(amt.abs(), "USD", vLocale));
            t.setBalanceAfterFormatted(currencyService.formatIn(after, "USD", vLocale));
        }
        return txs;
    }

    @Override
    @Transactional(readOnly = true)
    public long countMyTransactions(UUID userId) {
        return txRepository.countByWalletId(getOrCreate(userId).getId());
    }

    /* ============ Admin ============ */

    @Override
    @Transactional
    public WalletTransaction adminTopup(UUID userId, long amountCents, String description, String idempotencyKey) {
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        String desc = description != null && !description.isBlank() ? description : "Admin manual top-up";
        return deposit(userId, amountCents, null, key, desc);
    }

    @Override
    @Transactional
    public WalletTransaction adminAdjustEntry(UUID userId, long amountCents, String description,
            String idempotencyKey) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required for manual adjustments");
        }
        if (amountCents == 0) {
            throw new BusinessException("Adjustment amount cannot be zero");
        }
        // A manual adjustment can credit (+) or debit (-); record() applies the signed amount and
        // rejects it if it would overdraw the wallet. (DROP-603: negative adjustments used to fail.)
        String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
        return record(userId, "ADJUSTMENT", amountCents, null, null, key, "[Adjustment] " + description, null);
    }

    @Override
    @Transactional
    public Wallet adminGetWalletDetail(UUID userId) {
        Wallet w = getOrCreate(userId);
        w.setAvailableUsdCents(available(w));
        return w;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletTransaction> adminTransactions(UUID walletId, int page, int size) {
        return txRepository.findByWalletIdOrderByCreatedAtDesc(walletId, page, Math.min(size, 100));
    }

    @Override
    @Transactional(readOnly = true)
    public long countWalletTransactions(UUID walletId) {
        return txRepository.countByWalletId(walletId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Wallet> adminListWallets(String q, String status, String currency) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        return walletRepository.findAll().stream()
                .filter(w -> status == null || status.isBlank() || w.getStatus().equalsIgnoreCase(status))
                .filter(w -> currency == null || currency.isBlank()
                        || (w.getCurrencyDefault() != null && w.getCurrencyDefault().equalsIgnoreCase(currency)))
                .filter(w -> needle.isEmpty()
                        || (w.getUserEmail() != null && w.getUserEmail().toLowerCase().contains(needle))
                        || (w.getUserName() != null && w.getUserName().toLowerCase().contains(needle)))
                .sorted((a, b) -> Long.compare(b.getBalanceUsdCents(), a.getBalanceUsdCents())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WalletPage pageAdminWallets(String q, String status, String currency, int page, int size) {
        // Primario: OpenSearch (índice `wallets`) → página de IDs ordenada (reciente→antigua) y filtrada;
        // el saldo se lee fresco de la BD (dinero → consistencia estricta; el índice solo pagina/ordena/filtra).
        Optional<WalletSearchService.IdPage> idx = walletSearchService.pageIds(q, status, currency, page, size);
        if (idx.isPresent()) {
            Map<UUID, Wallet> byId = new HashMap<>();
            walletRepository.findAll().forEach(w -> byId.put(w.getId(), w));
            List<Wallet> items = idx.get().ids().stream().map(byId::get).filter(Objects::nonNull).toList();
            return new WalletPage(items, page, size, idx.get().total());
        }
        List<Wallet> all = adminListWallets(q, status, currency);
        int from = Math.min(Math.max(0, page) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new WalletPage(all.subList(from, to), page, size, all.size());
    }

    /* ============ Mutations ============ */

    @Override
    @Transactional
    public WalletTransaction deposit(UUID userId, long amountUsdCents, UUID paymentId, String idempotencyKey,
            String description) {
        if (amountUsdCents <= 0)
            throw new BusinessException("Deposit amount must be positive");
        return record(userId, "DEPOSIT", amountUsdCents, paymentId, null, idempotencyKey, description, null);
    }

    @Override
    @Transactional
    public WalletTransaction charge(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey,
            String description) {
        if (amountUsdCents <= 0)
            throw new BusinessException("Charge amount must be positive");
        Wallet w = require(userId);
        if (available(w) < amountUsdCents) {
            throw new BusinessException("WALLET_INSUFFICIENT_BALANCE", "Insufficient wallet balance");
        }
        return record(userId, "PAYMENT", -amountUsdCents, null, orderId, idempotencyKey, description, null);
    }

    @Override
    @Transactional
    public WalletTransaction refund(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey,
            String reason) {
        return record(userId, "REFUND", amountUsdCents, null, orderId, idempotencyKey, reason, null);
    }

    @Override
    @Transactional
    public WalletTransaction hold(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        Wallet w = require(userId);
        if (available(w) < amountUsdCents)
            throw new BusinessException("WALLET_INSUFFICIENT_BALANCE", "Insufficient balance to hold");
        w.setHoldUsdCents(w.getHoldUsdCents() + amountUsdCents);
        w = walletRepository.save(w);
        return record(userId, "HOLD", -amountUsdCents, null, orderId, idempotencyKey, "Hold for order", w);
    }

    @Override
    @Transactional
    public WalletTransaction release(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey) {
        Wallet w = require(userId);
        w.setHoldUsdCents(Math.max(0L, w.getHoldUsdCents() - amountUsdCents));
        w = walletRepository.save(w);
        return record(userId, "RELEASE", amountUsdCents, null, orderId, idempotencyKey, "Release hold", w);
    }

    /* ============ Internals ============ */

    private long available(Wallet w) {
        return Math.max(0L, w.getBalanceUsdCents() - w.getHoldUsdCents());
    }

    private Wallet require(UUID userId) {
        return walletRepository.findByUserId(userId).orElseThrow(() -> new NotFoundException("Wallet not found"));
    }

    private WalletTransaction record(UUID userId, String kind, long signedAmount, UUID paymentId, UUID orderId,
            String idempotencyKey, String description, Wallet preloadedWallet) {
        if (idempotencyKey != null) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Returning existing wallet tx for idempotency-key={}", idempotencyKey);
                return existing.get();
            }
        }
        Wallet w = preloadedWallet != null ? preloadedWallet : require(userId);
        // For HOLD/RELEASE we do NOT change balance, only hold counter (signedAmount is informational).
        boolean affectsBalance = !"HOLD".equals(kind) && !"RELEASE".equals(kind);
        long newBalance = w.getBalanceUsdCents();
        if (affectsBalance) {
            newBalance += signedAmount;
            if (newBalance < 0)
                throw new BusinessException("Wallet balance would go negative");
            w.setBalanceUsdCents(newBalance);
            walletRepository.save(w);
        }
        WalletTransaction tx = WalletTransaction.builder().walletId(w.getId()).kind(kind).amountUsdCents(signedAmount)
                .balanceAfterCents(newBalance).paymentId(paymentId).orderId(orderId).idempotencyKey(idempotencyKey)
                .status("COMPLETED").description(description).build();
        WalletTransaction saved = txRepository.save(tx);
        auditLogger.log("wallet." + kind.toLowerCase(), String.valueOf(userId),
                Map.of("amount", signedAmount, "balance_after", newBalance, "tx_id", saved.getId()));
        return saved;
    }
}
