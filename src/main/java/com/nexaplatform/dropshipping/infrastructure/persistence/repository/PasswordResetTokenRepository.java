package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {
    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    /** Consume (invalida) todos los tokens de reset AÚN vigentes de un usuario. Se llama al emitir uno nuevo. */
    @Modifying
    @Query("UPDATE PasswordResetTokenEntity t SET t.consumedAt = :now "
            + "WHERE t.user.id = :userId AND t.consumedAt IS NULL")
    void consumeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
