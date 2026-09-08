package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adaptador de Spring Data para el puerto {@code UserDeviceRepository}. */
public interface UserDeviceJpaRepositoryAdapter extends JpaRepository<UserDeviceEntity, UUID> {

    Optional<UserDeviceEntity> findByPushToken(String pushToken);

    List<UserDeviceEntity> findByUser_Id(UUID userId);

    void deleteByPushToken(String pushToken);

    void deleteByUser_IdAndPushToken(UUID userId, String pushToken);
}
