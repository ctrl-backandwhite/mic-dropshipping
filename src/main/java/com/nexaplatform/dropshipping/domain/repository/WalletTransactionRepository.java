package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.WalletTransaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link WalletTransaction} (the wallet ledger).
 * Implemented by an infrastructure adapter bridging to Spring Data JPA. Distinct
 * from the legacy Spring Data interface with the same simple name in
 * {@code infrastructure.persistence.repository} (kept for out-of-cluster consumers).
 */
public interface WalletTransactionRepository extends BaseRepository<WalletTransaction, WalletTransaction, UUID> {

    /** Ledger entries for a wallet, newest first, paged in the application layer. */
    List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, int page, int size);

    /** Total ledger entries for a wallet (for paging). */
    long countByWalletId(UUID walletId);

    /** Idempotent lookup: returns the existing entry for a given idempotency key. */
    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
