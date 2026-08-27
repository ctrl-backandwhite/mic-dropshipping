package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.TotpSecretEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TotpSecretRepository extends JpaRepository<TotpSecretEntity, UUID> {

    /**
     * Carga la fila con BLOQUEO de escritura (FOR UPDATE). La verificación del OTP es read-check-write: sin
     * este lock, dos peticiones concurrentes con el mismo código válido podrían pasar ambas (TOCTOU) y
     * reutilizar el mismo time-step. El lock serializa la comprobación anti-replay por usuario.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TotpSecretEntity t WHERE t.userId = :userId")
    Optional<TotpSecretEntity> findByIdForUpdate(@Param("userId") UUID userId);
}
