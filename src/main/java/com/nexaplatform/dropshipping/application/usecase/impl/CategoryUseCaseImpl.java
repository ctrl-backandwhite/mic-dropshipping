package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CategoryUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.CategoryUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.domain.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Catalog-category use case. Operates on the {@link Category} model and delegates
 * persistence to the domain port. Holds the logic that used to live in
 * {@code AdminCategoryService}: listing with computed product counts, toggling,
 * updating (slug-uniqueness, parent handling) and deleting (guarded by product /
 * sub-category references). The {@link EntityManager} is kept as a collaborator
 * for the aggregate count queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryUseCaseImpl implements CategoryUseCase {

    private final CategoryRepository categoryRepository;
    private final CategoryUpdateMapper categoryUpdateMapper;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<Category> findAll() {
        Map<UUID, Long> productCount = productCountByCategory();
        List<Category> categories = categoryRepository.findAll();
        for (Category c : categories) {
            c.setProductCount(productCount.getOrDefault(c.getId(), 0L));
        }
        return categories;
    }

    @Override
    @Transactional
    public Category toggle(UUID id) {
        Category model = getById(id);
        model.setActive(!Boolean.TRUE.equals(model.getActive()));
        Category saved = categoryRepository.update(model);
        saved.setProductCount(0L);
        return saved;
    }

    @Override
    @Transactional
    public Category update(Category model, UUID id) {
        Category existing = getById(id);
        if (!existing.getSlug().equals(model.getSlug())) {
            categoryRepository.findBySlug(model.getSlug()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new BusinessException("Slug already taken");
                }
            });
            existing.setSlug(model.getSlug());
        }
        categoryUpdateMapper.updateFromModel(model, existing);
        if (model.getParentId() != null) {
            if (model.getParentId().equals(id)) {
                throw new BusinessException("A category cannot be its own parent");
            }
            if (!categoryRepository.existsById(model.getParentId())) {
                throw new NotFoundException("Parent category");
            }
            existing.setParentId(model.getParentId());
        } else {
            existing.setParentId(null);
        }
        Category saved = categoryRepository.update(existing);
        log.info("::> [CATALOG] Category updated id={}", id);
        saved.setProductCount(productCount(id));
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        long products = productCount(id);
        if (products > 0) {
            throw new BusinessException("Cannot delete: " + products + " products still reference this category");
        }
        long children = em.createQuery("SELECT COUNT(c) FROM CategoryEntity c WHERE c.parent.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        if (children > 0) {
            throw new BusinessException("Cannot delete: " + children + " sub-categories still depend on this category");
        }
        categoryRepository.delete(id);
        log.info("::> [CATALOG] Category deleted id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public Category getById(UUID id) {
        Category model = categoryRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Category");
        }
        return model;
    }

    private Map<UUID, Long> productCountByCategory() {
        @SuppressWarnings("unchecked")
        List<Object[]> counts = em.createQuery(
                        "SELECT p.category.id, COUNT(p) FROM ProductEntity p WHERE p.category IS NOT NULL GROUP BY p.category.id")
                .getResultList();
        Map<UUID, Long> productCount = new HashMap<>();
        for (Object[] row : counts) {
            productCount.put((UUID) row[0], (Long) row[1]);
        }
        return productCount;
    }

    private long productCount(UUID id) {
        return em.createQuery("SELECT COUNT(p) FROM ProductEntity p WHERE p.category.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
    }
}
