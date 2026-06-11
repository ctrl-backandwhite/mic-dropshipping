package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.SourcingRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link SourcingRequest}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface SourcingRequestRepository extends BaseRepository<SourcingRequest, SourcingRequest, UUID> {

    /** Lists a user's requests, newest first (the "my requests" view). */
    List<SourcingRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Counts a user's requests created after the given instant (plan quota window). */
    long countByUserIdAndCreatedAtAfter(UUID userId, Instant from);
}
