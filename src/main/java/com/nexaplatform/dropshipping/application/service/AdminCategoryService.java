package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminCategoryMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use-case service for the Admin Categories endpoints. Holds all logic that used
 * to live inside {@code AdminCategoryController}: listing with cached product
 * counts, toggling, updating and deleting categories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;
    private final AdminCategoryMapper adminCategoryMapper;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<AdminCategoryDtoOut> list() {
        Map<UUID, Long> productCount = productCountByCategory();
        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition))
                .map(c -> adminCategoryMapper.toView(c, productCount.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public AdminCategoryDtoOut toggle(UUID id) {
        CategoryEntity c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category"));
        c.setActive(!c.isActive());
        categoryRepository.save(c);
        return adminCategoryMapper.toView(c, 0L);
    }

    /** Updates a category — slug, names, parent, position, icon. */
    @Transactional
    public AdminCategoryDtoOut update(UUID id, AdminCategoryUpsertDtoIn req) {
        CategoryEntity c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category"));
        if (!c.getSlug().equals(req.getSlug())) {
            categoryRepository.findBySlug(req.getSlug()).ifPresent(other -> {
                if (!other.getId().equals(id)) throw new BusinessException("Slug already taken");
            });
            c.setSlug(req.getSlug());
        }
        c.setNameZh(req.getNameZh());
        if (req.getIcon() != null) c.setIcon(req.getIcon());
        if (req.getPosition() != null) c.setPosition(req.getPosition());
        if (req.getActive() != null) c.setActive(req.getActive());
        if (req.getParentId() != null) {
            if (req.getParentId().equals(id)) throw new BusinessException("A category cannot be its own parent");
            c.setParent(categoryRepository.findById(req.getParentId())
                    .orElseThrow(() -> new NotFoundException("Parent category")));
        } else {
            c.setParent(null);
        }
        c.setTranslations(buildTranslations(c, req.getNames()));
        categoryRepository.save(c);
        log.info("::> [CATALOG] Category updated id={}", id);
        return adminCategoryMapper.toView(c, productCount(id));
    }

    /** Deletes a category — fails if products or sub-categories still reference it. */
    @Transactional
    public void delete(UUID id) {
        CategoryEntity c = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category"));
        long products = productCount(id);
        if (products > 0) {
            throw new BusinessException("Cannot delete: " + products + " products still reference this category");
        }
        long children = em.createQuery("SELECT COUNT(c) FROM CategoryEntity c WHERE c.parent.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
        if (children > 0) {
            throw new BusinessException("Cannot delete: " + children + " sub-categories still depend on this category");
        }
        categoryRepository.delete(c);
        log.info("::> [CATALOG] Category deleted id={}", id);
    }

    private Map<UUID, Long> productCountByCategory() {
        @SuppressWarnings("unchecked")
        List<Object[]> counts = em.createQuery(
                        "SELECT p.category.id, COUNT(p) FROM ProductEntity p WHERE p.category IS NOT NULL GROUP BY p.category.id")
                .getResultList();
        Map<UUID, Long> productCount = new HashMap<>();
        for (Object[] row : counts) productCount.put((UUID) row[0], (Long) row[1]);
        return productCount;
    }

    private long productCount(UUID id) {
        return em.createQuery("SELECT COUNT(p) FROM ProductEntity p WHERE p.category.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
    }

    private List<CategoryTranslationEntity> buildTranslations(CategoryEntity c, Map<String, String> names) {
        List<CategoryTranslationEntity> out = new ArrayList<>();
        if (names == null) return out;
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            out.add(CategoryTranslationEntity.builder()
                    .category(c)
                    .language(e.getKey().toLowerCase())
                    .name(e.getValue())
                    .build());
        }
        return out;
    }
}
