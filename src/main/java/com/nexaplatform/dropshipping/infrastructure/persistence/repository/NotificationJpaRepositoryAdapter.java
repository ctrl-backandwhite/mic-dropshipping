package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code NotificationRepository} domain port. */
public interface NotificationJpaRepositoryAdapter extends JpaRepository<NotificationEntity, UUID> {

    List<NotificationEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    /** No-leídas de la bandeja de entrada: excluye archivadas y las que están en la papelera. */
    long countByUser_IdAndReadAtIsNullAndArchivedAtIsNullAndDeletedAtIsNull(UUID userId);
}
