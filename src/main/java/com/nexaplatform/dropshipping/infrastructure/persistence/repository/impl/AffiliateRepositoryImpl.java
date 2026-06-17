package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Affiliate;
import com.nexaplatform.dropshipping.domain.repository.AffiliateRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.AffiliateEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link AffiliateRepository} domain port
 * on top of Spring Data JPA. Owns the persistence-only concern of resolving the
 * {@code user} relation from the flattened {@code userId} on a managed entity.
 */
@Repository
@RequiredArgsConstructor
public class AffiliateRepositoryImpl implements AffiliateRepository {

    private final AffiliateEntityMapper affiliateEntityMapper;
    private final AffiliateJpaRepositoryAdapter affiliateJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public Affiliate save(Affiliate model) {
        AffiliateEntity entity = resolveEntity(model);
        applyModel(entity, model);
        AffiliateEntity saved = affiliateJpaRepositoryAdapter.save(entity);
        return affiliateEntityMapper.toDomain(saved);
    }

    @Override
    public List<Affiliate> findAll() {
        return affiliateEntityMapper.toDomainList(affiliateJpaRepositoryAdapter.findAll());
    }

    @Override
    public Optional<Affiliate> findByUserId(UUID userId) {
        return affiliateJpaRepositoryAdapter.findByUser_Id(userId).map(affiliateEntityMapper::toDomain);
    }

    @Override
    public Affiliate update(Affiliate model) {
        return this.save(model);
    }

    @Override
    public Affiliate getById(UUID id) {
        return affiliateJpaRepositoryAdapter.findById(id).map(affiliateEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        affiliateJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return affiliateJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private AffiliateEntity resolveEntity(Affiliate model) {
        if (model.getId() != null) {
            return affiliateJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Affiliate"));
        }
        return new AffiliateEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the user relation. */
    private void applyModel(AffiliateEntity entity, Affiliate model) {
        // Campos escalares vía MapStruct; la relación gestionada (user) se resuelve abajo
        // porque requiere un lookup de repositorio.
        affiliateEntityMapper.updateEntity(entity, model);

        if (model.getUserId() != null) {
            entity.setUser(userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User")));
        }
    }
}
