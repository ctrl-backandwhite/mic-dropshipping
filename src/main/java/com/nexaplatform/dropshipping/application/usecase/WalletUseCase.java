package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for wallets and the wallet ledger; operates on the {@link Wallet}
 * and {@link WalletTransaction} domain models. All money mutations are recorded
 * in the single ledger ({@code wallet_transaction}); idempotency keys prevent
 * double credit/debit when the same recharge or refund is delivered twice.
 */
public interface WalletUseCase extends BaseUseCase<Wallet, Wallet, UUID> {

    /* ============ Read ============ */

    /** Returns the user's wallet, creating an empty ACTIVE one on first access. */
    Wallet getOrCreate(UUID userId);

    /** Wallet snapshot for the authenticated user (computes the available balance). */
    Wallet getMyWallet(UUID userId);

    /** Paginated wallet transactions for the authenticated user, newest first. */
    List<WalletTransaction> getMyTransactions(UUID userId, int page, int size);

    /** Total transactions for the authenticated user's wallet (for paging). */
    long countMyTransactions(UUID userId);

    /* ============ Admin ============ */

    /** Admin manual top-up. Settled as a deposit with a MANUAL_TOPUP description. */
    WalletTransaction adminTopup(UUID userId, long amountCents, String description, String idempotencyKey);

    /** Admin manual adjustment ({@code amountCents} may be negative; description required). */
    WalletTransaction adminAdjustEntry(UUID userId, long amountCents, String description, String idempotencyKey);

    /**
     * Admin wallet detail for a given user. Returns the wallet with the computed
     * available balance ({@code balance - hold}) and the user enrichment filled,
     * creating an empty ACTIVE wallet on first access (mirrors {@link #getOrCreate}).
     */
    Wallet adminGetWalletDetail(UUID userId);

    /** Admin paginated wallet transactions for a wallet, newest first. */
    List<WalletTransaction> adminTransactions(UUID walletId, int page, int size);

    /** Total transactions for a wallet (for paging). */
    long countWalletTransactions(UUID walletId);

    /** Admin filtered + sorted wallet listing (balance desc); paging is applied here. */
    List<Wallet> adminListWallets(String q, String status, String currency);

    /* ============ Mutations (money) ============ */

    /** Credit funds (recharge from a payment provider). */
    WalletTransaction deposit(UUID userId, long amountUsdCents, UUID paymentId, String idempotencyKey,
            String description);

    /** Debit funds (from order checkout). */
    WalletTransaction charge(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey, String description);

    /** Refund (credit back from a previous charge). */
    WalletTransaction refund(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey, String reason);

    /** Hold funds (e.g. for checkout pending payment confirmation). */
    WalletTransaction hold(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey);

    /** Release a previously placed hold. */
    WalletTransaction release(UUID userId, long amountUsdCents, UUID orderId, String idempotencyKey);
}
