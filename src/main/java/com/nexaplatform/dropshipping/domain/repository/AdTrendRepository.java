package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.AdTrend;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link AdTrend}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface AdTrendRepository extends BaseRepository<AdTrend, AdTrend, UUID> {

    /** Lists all ad trends ordered by score descending. */
    List<AdTrend> findAllByScoreDesc();

    /** Lists ad trends for a given source ordered by score descending. */
    List<AdTrend> findBySourceByScoreDesc(String source);
}
