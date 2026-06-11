package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code NotificationRepository} domain port. */
public interface NotificationJpaRepositoryAdapter extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    long countByUser_IdAndReadAtIsNull(UUID userId);
}
