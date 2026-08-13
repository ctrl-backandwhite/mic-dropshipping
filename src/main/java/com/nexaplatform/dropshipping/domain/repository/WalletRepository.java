package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Wallet;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Wallet}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface with the same simple name in
 * {@code infrastructure.persistence.repository} (kept for out-of-cluster consumers).
 */
public interface WalletRepository extends BaseRepository<Wallet, Wallet, UUID> {

    /** Wallet owned by the given user (one-to-one). */
    Optional<Wallet> findByUserId(UUID userId);

    /** Igual que {@link #findByUserId} pero con BLOQUEO de fila (FOR UPDATE) para movimientos de saldo. */
    Optional<Wallet> findByUserIdForUpdate(UUID userId);
}
