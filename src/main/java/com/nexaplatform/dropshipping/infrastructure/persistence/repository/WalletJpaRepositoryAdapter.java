package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code WalletRepository} domain port. */
public interface WalletJpaRepositoryAdapter extends JpaRepository<WalletEntity, UUID> {

    Optional<WalletEntity> findByUser_Id(UUID userId);

    /**
     * Carga la wallet con BLOQUEO de fila (SELECT … FOR UPDATE). Serializa los movimientos que tocan el
     * saldo (cobros/reembolsos) dentro de su transacción: sin esto, dos checkouts concurrentes hacían
     * read-check-write sobre el mismo saldo (lost update) y ambos quedaban pagados con un solo débito.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.user.id = :userId")
    Optional<WalletEntity> findByUser_IdForUpdate(@Param("userId") UUID userId);

    /**
     * Aplica el movimiento sobre el saldo <b>en la propia base</b>, en una sola sentencia, y solo si el
     * saldo da para ello. Devuelve 1 si se aplicó y 0 si no había suficiente.
     *
     * <p><b>Por qué hace falta, si ya existe el bloqueo de arriba.</b> Un pentest con ocho checkouts
     * simultáneos (14-ago-2026) creó cuatro pedidos PAID cobrando uno solo: los apuntes concurrentes
     * escribieron todos el mismo {@code balance_after_cents}, señal inequívoca de que la secuencia
     * leer-comprobar-escribir no se estaba serializando pese al {@code FOR UPDATE}. Esta sentencia no
     * depende de ello: el {@code WHERE} con la condición de saldo y la resta van en la MISMA operación
     * atómica, así que dos transacciones no pueden partir del mismo saldo. Para los abonos, {@code delta}
     * es positivo y la condición se cumple siempre.
     *
     * <p>El bloqueo pesimista se mantiene porque sigue serializando el resto del apunte (idempotencia y
     * escritura en el libro), pero la integridad del saldo ya no descansa en él.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_usd_cents = balance_usd_cents + :delta, updated_at = now()"
            + " WHERE user_id = :userId AND balance_usd_cents + :delta >= 0", nativeQuery = true)
    int applyBalanceDelta(@Param("userId") UUID userId, @Param("delta") long delta);

    /** Saldo actual, leído después de aplicar el movimiento para dejarlo escrito en el apunte. */
    @Query(value = "SELECT balance_usd_cents FROM wallet WHERE user_id = :userId", nativeQuery = true)
    Optional<Long> currentBalance(@Param("userId") UUID userId);
}
