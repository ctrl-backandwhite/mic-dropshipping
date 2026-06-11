package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Affiliate;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Affiliate}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface AffiliateRepository extends BaseRepository<Affiliate, Affiliate, UUID> {

    /** The affiliate account owned by a user, if any (drives the get-or-create flow). */
    Optional<Affiliate> findByUserId(UUID userId);
}
