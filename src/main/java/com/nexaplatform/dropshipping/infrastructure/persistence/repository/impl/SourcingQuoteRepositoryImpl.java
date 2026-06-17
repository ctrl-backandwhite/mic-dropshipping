package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.repository.SourcingQuoteRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingQuoteEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SourcingQuoteEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingAgentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingQuoteJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingRequestJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SourcingQuoteRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concerns the domain
 * model abstracts away: resolving the {@code request} relation from the flattened
 * {@code requestId} and the optional {@code agent} relation from the nested
 * agent's id. The list finder preserves the legacy ordering (cheapest first).
 */
@Repository
@RequiredArgsConstructor
public class SourcingQuoteRepositoryImpl implements SourcingQuoteRepository {

    private final SourcingQuoteEntityMapper sourcingQuoteEntityMapper;
    private final SourcingQuoteJpaRepositoryAdapter sourcingQuoteJpaRepositoryAdapter;
    private final SourcingRequestJpaRepositoryAdapter sourcingRequestJpaRepositoryAdapter;
    private final SourcingAgentJpaRepositoryAdapter sourcingAgentJpaRepositoryAdapter;

    @Override
    public SourcingQuote save(SourcingQuote model) {
        SourcingQuoteEntity entity = resolveEntity(model);
        applyModel(entity, model);
        SourcingQuoteEntity saved = sourcingQuoteJpaRepositoryAdapter.save(entity);
        return sourcingQuoteEntityMapper.toDomain(saved);
    }

    @Override
    public SourcingQuote update(SourcingQuote model) {
        return this.save(model);
    }

    @Override
    public List<SourcingQuote> findByRequestIdOrderByPriceUsdCentsAsc(UUID requestId) {
        return sourcingQuoteEntityMapper
                .toDomainList(sourcingQuoteJpaRepositoryAdapter.findByRequest_IdOrderByPriceUsdCentsAsc(requestId));
    }

    @Override
    public SourcingQuote getById(UUID id) {
        return sourcingQuoteJpaRepositoryAdapter.findById(id).map(sourcingQuoteEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        sourcingQuoteJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return sourcingQuoteJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private SourcingQuoteEntity resolveEntity(SourcingQuote model) {
        if (model.getId() != null) {
            return sourcingQuoteJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Quote"));
        }
        return new SourcingQuoteEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving request and agent. */
    private void applyModel(SourcingQuoteEntity entity, SourcingQuote model) {
        // Campos escalares vía MapStruct; las relaciones gestionadas (request, agent) se resuelven abajo
        // porque requieren lookups de repositorio.
        sourcingQuoteEntityMapper.updateEntity(entity, model);

        if (model.getRequestId() != null) {
            entity.setRequest(sourcingRequestJpaRepositoryAdapter.findById(model.getRequestId())
                    .orElseThrow(() -> new NotFoundException("Sourcing request")));
        }
        if (model.getAgent() != null && model.getAgent().getId() != null) {
            entity.setAgent(sourcingAgentJpaRepositoryAdapter.findById(model.getAgent().getId())
                    .orElseThrow(() -> new NotFoundException("Agent")));
        } else {
            entity.setAgent(null);
        }
    }
}
