package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code SourcingAgentRepository} domain port. */
public interface SourcingAgentJpaRepositoryAdapter extends JpaRepository<AgentProfileEntity, UUID> {

    List<AgentProfileEntity> findByActiveTrueOrderBySatisfactionDesc();
}
