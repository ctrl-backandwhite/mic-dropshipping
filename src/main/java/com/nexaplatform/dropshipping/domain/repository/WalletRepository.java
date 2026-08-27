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

    /**
     * Suma {@code delta} al saldo <b>en una sola sentencia atómica</b> y solo si el resultado no queda en
     * negativo. Devuelve {@code true} si se aplicó, {@code false} si no había saldo suficiente.
     *
     * <p>Es la única forma de mover el saldo que garantiza que dos cobros simultáneos no parten de la
     * misma lectura. Leer, comprobar en Java y guardar es lo que permitió pagar cuatro pedidos con saldo
     * para uno solo.
     */
    boolean applyBalanceDelta(UUID userId, long delta);

    /** Saldo actual en céntimos USD, para dejarlo escrito en el apunte tras aplicar el movimiento. */
    long currentBalanceCents(UUID userId);
}
