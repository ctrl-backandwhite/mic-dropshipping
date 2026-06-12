package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.domain.repository.SourcingAgentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SourcingAgentEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingAgentJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SourcingAgentRepository} domain
 * port on top of Spring Data JPA. The list finder preserves the legacy ordering
 * (active agents by satisfaction).
 */
@Repository
@RequiredArgsConstructor
public class SourcingAgentRepositoryImpl implements SourcingAgentRepository {

    private final SourcingAgentEntityMapper sourcingAgentEntityMapper;
    private final SourcingAgentJpaRepositoryAdapter sourcingAgentJpaRepositoryAdapter;

    @Override
    public List<SourcingAgent> findByActiveTrueOrderBySatisfactionDesc() {
        return sourcingAgentEntityMapper
                .toDomainList(sourcingAgentJpaRepositoryAdapter.findByActiveTrueOrderBySatisfactionDesc());
    }

    @Override
    public SourcingAgent getById(UUID id) {
        return sourcingAgentJpaRepositoryAdapter.findById(id).map(sourcingAgentEntityMapper::toDomain).orElse(null);
    }

    @Override
    public boolean existsById(UUID id) {
        return sourcingAgentJpaRepositoryAdapter.existsById(id);
    }
}
