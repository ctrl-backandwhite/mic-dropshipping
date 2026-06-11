package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code AffiliateRepository} domain port. */
public interface AffiliateJpaRepositoryAdapter extends JpaRepository<AffiliateEntity, UUID> {

    Optional<AffiliateEntity> findByUser_Id(UUID userId);
}
