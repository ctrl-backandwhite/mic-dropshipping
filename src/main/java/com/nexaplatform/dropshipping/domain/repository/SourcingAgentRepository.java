package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.SourcingAgent;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link SourcingAgent}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * {@code AgentProfileRepository} Spring Data interface in
 * {@code infrastructure.persistence.repository}.
 */
public interface SourcingAgentRepository extends BaseRepository<SourcingAgent, SourcingAgent, UUID> {

    /** Lists the active agents ordered by satisfaction (marketplace listing). */
    List<SourcingAgent> findByActiveTrueOrderBySatisfactionDesc();
}
