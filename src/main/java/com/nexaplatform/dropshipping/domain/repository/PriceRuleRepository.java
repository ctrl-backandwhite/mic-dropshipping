package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.PriceRule;

import java.util.UUID;

/**
 * Domain repository port for {@link PriceRule}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface PriceRuleRepository extends BaseRepository<PriceRule, PriceRule, UUID> {
}
