package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import com.nexaplatform.dropshipping.domain.repository.SourcingRequestRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingRequestEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SourcingRequestEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SourcingRequestJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SourcingRequestRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concern the domain
 * model abstracts away: resolving the {@code user} relation from the flattened
 * {@code userId}. The list finder preserves the legacy ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class SourcingRequestRepositoryImpl implements SourcingRequestRepository {

    private final SourcingRequestEntityMapper sourcingRequestEntityMapper;
    private final SourcingRequestJpaRepositoryAdapter sourcingRequestJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public SourcingRequest save(SourcingRequest model) {
        SourcingRequestEntity entity = resolveEntity(model);
        applyModel(entity, model);
        SourcingRequestEntity saved = sourcingRequestJpaRepositoryAdapter.save(entity);
        return sourcingRequestEntityMapper.toDomain(saved);
    }

    @Override
    public SourcingRequest update(SourcingRequest model) {
        return this.save(model);
    }

    @Override
    public List<SourcingRequest> findByUserIdOrderByCreatedAtDesc(UUID userId) {
        return sourcingRequestEntityMapper
                .toDomainList(sourcingRequestJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public long countByUserIdAndCreatedAtAfter(UUID userId, Instant from) {
        return sourcingRequestJpaRepositoryAdapter.countByUser_IdAndCreatedAtAfter(userId, from);
    }

    @Override
    public SourcingRequest getById(UUID id) {
        return sourcingRequestJpaRepositoryAdapter.findById(id).map(sourcingRequestEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        sourcingRequestJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return sourcingRequestJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private SourcingRequestEntity resolveEntity(SourcingRequest model) {
        if (model.getId() != null) {
            return sourcingRequestJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Sourcing request"));
        }
        return new SourcingRequestEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the user relation. */
    private void applyModel(SourcingRequestEntity entity, SourcingRequest model) {
        // Relación gestionada resuelta vía lookup de repositorio.
        if (model.getUserId() != null) {
            entity.setUser(userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User")));
        }
        // Campos escalares vía MapStruct.
        sourcingRequestEntityMapper.updateEntity(entity, model);
    }
}
