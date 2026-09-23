package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateAttributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateAttributionRepository extends JpaRepository<AffiliateAttributionEntity, UUID> {

    /** Most recent non-expired attribution for an anonymous visitor (last-click). */
    Optional<AffiliateAttributionEntity> findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(String visitorToken,
            Instant now);

    /** Most recent non-expired attribution already bound to a logged-in customer. */
    Optional<AffiliateAttributionEntity> findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(
            UUID referredUserId, Instant now);
}
