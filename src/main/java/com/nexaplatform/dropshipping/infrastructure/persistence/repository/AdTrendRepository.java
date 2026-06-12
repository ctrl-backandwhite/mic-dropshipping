package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AdTrendRepository extends JpaRepository<AdTrendEntity, UUID> {
    List<AdTrendEntity> findBySourceOrderByScoreDesc(String source);

    List<AdTrendEntity> findAllByOrderByScoreDesc();
}
