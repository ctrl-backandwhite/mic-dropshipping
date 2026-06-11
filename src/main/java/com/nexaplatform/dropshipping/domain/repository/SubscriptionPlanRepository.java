package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;

import java.util.UUID;

/**
 * Domain repository port for {@link SubscriptionPlan}. Implemented by an
 * infrastructure adapter that bridges to Spring Data JPA. Distinct from the
 * legacy Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface SubscriptionPlanRepository extends BaseRepository<SubscriptionPlan, SubscriptionPlan, UUID> {
}
