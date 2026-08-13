package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
