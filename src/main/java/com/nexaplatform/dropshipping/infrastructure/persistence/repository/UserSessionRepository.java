package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a las sesiones/dispositivos conectados de los usuarios. */
public interface UserSessionRepository extends JpaRepository<UserSessionEntity, UUID> {

    List<UserSessionEntity> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);

    Optional<UserSessionEntity> findByDeviceToken(String deviceToken);
}
