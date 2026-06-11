package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.IntelligenceAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code IntelligenceAlertRepository} domain port. */
public interface IntelligenceAlertJpaRepositoryAdapter extends JpaRepository<IntelligenceAlertEntity, UUID> {

    List<IntelligenceAlertEntity> findByUser_IdAndActiveTrue(UUID userId);
}
