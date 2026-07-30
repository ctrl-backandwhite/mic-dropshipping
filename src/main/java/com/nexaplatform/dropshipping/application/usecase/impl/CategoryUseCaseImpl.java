package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CategoryUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.CategoryUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.domain.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategorySearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategorySearchService.IndexedCategory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORIES_FLAT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORY_TREE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final CategoryIndexer categoryIndexer;
    private final CategorySearchService categorySearchService;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<Category> findAll() {
        Map<UUID, Long> productCount = productCountByCategory();
        // DROP: list from the OpenSearch index (kept in sync on every change), falling back to the DB
        // when the index is empty or OpenSearch is unavailable. productCount is not indexed, so it is
        // resolved here with a single GROUP BY.
        Optional<List<IndexedCategory>> indexed = categorySearchService.listFromIndex(null);
        List<Category> categories = indexed.isPresent()
                ? indexed.get().stream().map(this::fromIndexed).toList()
                : categoryRepository.findAll();
        for (Category c : categories) {
            c.setProductCount(productCount.getOrDefault(c.getId(), 0L));
        }
        return categories;
    }

    /** Maps a category document read from the OpenSearch index to the domain model. */
    private Category fromIndexed(IndexedCategory r) {
        Map<String, String> names = new LinkedHashMap<>();
        if (r.nameEs() != null) {
            names.put("es", r.nameEs());
        }
        if (r.nameEn() != null) {
            names.put("en", r.nameEn());
        }
        if (r.namePt() != null) {
            names.put("pt", r.namePt());
        }
        return Category.builder().id(r.id()).slug(r.slug()).nameZh(r.nameZh()).names(names).icon(r.icon())
                .position(r.position()).active(r.active()).parentId(r.parentId()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Category> findAllPaged(String q, Boolean hasProducts, Pageable pageable) {
        Map<UUID, Long> productCount = productCountByCategory();
        if (hasProducts == null) {
            // Camino eficiente: una GROUP BY para toda la página (sin N+1) y paginación en BD.
            Page<Category> page = categoryRepository.search(q, pageable);
            page.getContent().forEach(c -> c.setProductCount(productCount.getOrDefault(c.getId(), 0L)));
            return page;
        }
        // Filtro con/sin productos: el conteo se calcula tras la query, así que filtramos en memoria
        // (las categorías son pocas) y paginamos sobre el resultado filtrado.
        List<Category> all = categoryRepository.search(q, PageRequest.of(0, 100_000)).getContent();
        List<Category> filtered = new ArrayList<>();
        for (Category c : all) {
            long n = productCount.getOrDefault(c.getId(), 0L);
            c.setProductCount(n);
            if (hasProducts ? n > 0 : n == 0) {
                filtered.add(c);
            }
        }
        int start = (int) Math.min(pageable.getOffset(), filtered.size());
        int end = Math.min(start + pageable.getPageSize(), filtered.size());
        return new PageImpl<>(filtered.subList(start, end), pageable, filtered.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category> findWithProducts() {
        return findAll().stream().filter(c -> c.getProductCount() > 0).toList();
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public int setActiveBulk(List<UUID> ids, boolean active) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (UUID id : ids) {
            // getById lanza si el id ya no existe, así que la comprobación de nulo no se alcanzaba nunca y
            // UNA categoría borrada entre medias tumbaba el lote entero con un 404. Una selección con un id
            // obsoleto es lo normal cuando dos administradores trabajan a la vez: se salta y sigue.
            Category c;
            try {
                c = getById(id);
            } catch (NotFoundException e) {
                log.warn("Activación en lote: la categoría {} ya no existe, se omite", id);
                continue;
            }
            if (Boolean.valueOf(active).equals(c.getActive())) {
                continue;
            }
            c.setActive(active);
            categoryRepository.update(c);
            categoryIndexer.indexCategory(id);
            updated++;
        }
        return updated;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public Category toggle(UUID id) {
        Category model = getById(id);
        model.setActive(!Boolean.TRUE.equals(model.getActive()));
        Category saved = categoryRepository.update(model);
        categoryIndexer.indexCategory(id);
        saved.setProductCount(0L);
        return saved;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
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
        categoryIndexer.indexCategory(id);
        log.info("::> [CATALOG] Category updated id={}", id);
        saved.setProductCount(productCount(id));
        return saved;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
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
        categoryIndexer.deleteFromIndex(id);
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
