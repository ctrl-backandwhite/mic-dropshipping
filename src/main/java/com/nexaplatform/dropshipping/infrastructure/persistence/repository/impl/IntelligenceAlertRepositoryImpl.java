package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import com.nexaplatform.dropshipping.domain.repository.IntelligenceAlertRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.IntelligenceAlertEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.IntelligenceAlertEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.IntelligenceAlertJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link IntelligenceAlertRepository}
 * domain port on top of Spring Data JPA. Owns the persistence-only concerns the
 * domain model abstracts away: resolving the required {@code user} owner and the
 * optional {@code category} relation from their flattened ids. A missing category
 * is tolerated (left null), preserving the legacy controller behaviour.
 */
@Repository
@RequiredArgsConstructor
public class IntelligenceAlertRepositoryImpl implements IntelligenceAlertRepository {

    private final IntelligenceAlertEntityMapper intelligenceAlertEntityMapper;
    private final IntelligenceAlertJpaRepositoryAdapter intelligenceAlertJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;

    @Override
    public IntelligenceAlert save(IntelligenceAlert model) {
        IntelligenceAlertEntity entity = resolveEntity(model);
        applyModel(entity, model);
        IntelligenceAlertEntity saved = intelligenceAlertJpaRepositoryAdapter.save(entity);
        return intelligenceAlertEntityMapper.toDomain(saved);
    }

    @Override
    public List<IntelligenceAlert> findAll() {
        return intelligenceAlertEntityMapper.toDomainList(intelligenceAlertJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<IntelligenceAlert> findActiveByUser(UUID userId) {
        return intelligenceAlertEntityMapper
                .toDomainList(intelligenceAlertJpaRepositoryAdapter.findByUser_IdAndActiveTrue(userId));
    }

    @Override
    public IntelligenceAlert update(IntelligenceAlert model) {
        return this.save(model);
    }

    @Override
    public IntelligenceAlert getById(UUID id) {
        return intelligenceAlertJpaRepositoryAdapter.findById(id).map(intelligenceAlertEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        intelligenceAlertJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return intelligenceAlertJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private IntelligenceAlertEntity resolveEntity(IntelligenceAlert model) {
        if (model.getId() != null) {
            return intelligenceAlertJpaRepositoryAdapter.findById(model.getId())
                    .orElseGet(IntelligenceAlertEntity::new);
        }
        return new IntelligenceAlertEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving user and category. */
    private void applyModel(IntelligenceAlertEntity entity, IntelligenceAlert model) {
        // Campos escalares vía MapStruct; las relaciones gestionadas (user, category) se resuelven
        // abajo porque requieren lookups de repositorio.
        intelligenceAlertEntityMapper.updateEntity(entity, model);
        entity.setUser(resolveUser(model.getUserId()));
        entity.setCategory(resolveCategory(model.getCategoryId()));
    }

    /** Resolves the alert owner from its id (required by the entity). */
    private UserEntity resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElseThrow();
    }

    /** Resolves the optional category from its id; a missing category is left null. */
    private CategoryEntity resolveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId).orElse(null);
    }
}
