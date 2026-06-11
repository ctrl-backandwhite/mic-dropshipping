package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code AdTrendRepository} domain port. */
public interface AdTrendJpaRepositoryAdapter extends JpaRepository<AdTrendEntity, UUID> {

    List<AdTrendEntity> findAllByOrderByScoreDesc();

    List<AdTrendEntity> findBySourceOrderByScoreDesc(String source);
}
