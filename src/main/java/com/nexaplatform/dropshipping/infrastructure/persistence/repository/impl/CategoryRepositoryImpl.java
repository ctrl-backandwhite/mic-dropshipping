package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.domain.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CategoryEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link CategoryRepository} domain port
 * on top of Spring Data JPA. Owns the persistence-only concerns the domain model
 * abstracts away: resolving the self-referencing {@code parent} from
 * {@code parentId} and rebuilding the {@code translations} collection from the
 * flattened {@code names} map. {@code findAll} preserves the legacy admin-list
 * ordering (by position).
 */
@Repository
@RequiredArgsConstructor
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryEntityMapper categoryEntityMapper;
    private final CategoryJpaRepositoryAdapter categoryJpaRepositoryAdapter;

    @Override
    public Category save(Category model) {
        CategoryEntity entity = resolveEntity(model);
        applyModel(entity, model);
        CategoryEntity saved = categoryJpaRepositoryAdapter.save(entity);
        return categoryEntityMapper.toDomain(saved);
    }

    @Override
    public List<Category> findAll() {
        return categoryJpaRepositoryAdapter.findAll().stream()
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition)).map(categoryEntityMapper::toDomain)
                .toList();
    }

    @Override
    public Category update(Category model) {
        return this.save(model);
    }

    @Override
    public Category getById(UUID id) {
        return categoryJpaRepositoryAdapter.findById(id).map(categoryEntityMapper::toDomain).orElse(null);
    }

    @Override
    public org.springframework.data.domain.Page<Category> search(String q,
            org.springframework.data.domain.Pageable pageable) {
        String filter = (q == null || q.isBlank()) ? null : q.trim();
        return categoryJpaRepositoryAdapter.search(filter, pageable).map(categoryEntityMapper::toDomain);
    }

    @Override
    public Optional<Category> findBySlug(String slug) {
        return categoryJpaRepositoryAdapter.findBySlug(slug).map(categoryEntityMapper::toDomain);
    }

    @Override
    public void delete(UUID id) {
        categoryJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return categoryJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private CategoryEntity resolveEntity(Category model) {
        if (model.getId() != null) {
            return categoryJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Category"));
        }
        return new CategoryEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving parent and translations. */
    private void applyModel(CategoryEntity entity, Category model) {
        entity.setSlug(model.getSlug());
        entity.setSource(model.getSource());
        entity.setExternalId(model.getExternalId());
        entity.setNameZh(model.getNameZh());
        entity.setPosition(model.getPosition() != null ? model.getPosition() : 0);
        entity.setActive(model.getActive() != null && model.getActive());
        entity.setIcon(model.getIcon());
        entity.setParent(resolveParent(model.getParentId()));
        upsertTranslations(entity, model.getNames());
    }

    /** Resolves the parent category from its id, failing if it does not exist. */
    private CategoryEntity resolveParent(UUID parentId) {
        if (parentId == null) {
            return null;
        }
        return categoryJpaRepositoryAdapter.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Parent category"));
    }

    /**
     * Upserts translation rows <em>in place</em> on the managed collection: existing languages
     * have their name updated, genuinely new languages are added and languages no longer present
     * are dropped (orphanRemoval deletes them). Reusing the existing rows is what prevents the
     * duplicate-key violation on {@code (category_id, language)} that replacing the whole
     * collection caused on every edit.
     */
    private void upsertTranslations(CategoryEntity c, Map<String, String> names) {
        if (c.getTranslations() == null) {
            c.setTranslations(new ArrayList<>());
        }
        Map<String, String> wanted = new HashMap<>();
        if (names != null) {
            for (Map.Entry<String, String> e : names.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    wanted.put(e.getKey().toLowerCase(), e.getValue());
                }
            }
        }
        List<CategoryTranslationEntity> current = c.getTranslations();
        // Drop languages no longer wanted (orphanRemoval deletes the row).
        current.removeIf(tr -> !wanted.containsKey(tr.getLanguage()));
        // Update the rows we keep, consuming them from `wanted` so only new languages remain.
        for (CategoryTranslationEntity tr : current) {
            String name = wanted.remove(tr.getLanguage());
            if (name != null) {
                tr.setName(name);
            }
        }
        // Add the genuinely new languages.
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            current.add(CategoryTranslationEntity.builder().category(c).language(e.getKey()).name(e.getValue())
                    .build());
        }
    }
}
