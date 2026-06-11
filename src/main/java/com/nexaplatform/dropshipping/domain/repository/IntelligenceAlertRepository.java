package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link IntelligenceAlert}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface IntelligenceAlertRepository extends BaseRepository<IntelligenceAlert, IntelligenceAlert, UUID> {

    /** Lists the active alerts owned by the given user. */
    List<IntelligenceAlert> findActiveByUser(UUID userId);
}
