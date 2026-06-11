package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.PodDesign;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link PodDesign}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface PodDesignRepository extends BaseRepository<PodDesign, PodDesign, UUID> {

    /** Lists the designs owned by a user, newest first. */
    List<PodDesign> findByUserId(UUID userId);
}
