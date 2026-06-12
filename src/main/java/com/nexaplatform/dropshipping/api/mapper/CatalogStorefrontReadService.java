package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared storefront catalog read-projection helper. Holds the category/supplier/
 * variant view logic and the SQL-backed product listing so that both
 * {@code StorefrontCatalogController} and {@code PartnerCatalogController} expose
 * identical shapes without one controller injecting the other (the partner→storefront
 * controller dependency is replaced by this shared collaborator + the CatalogUseCase).
 */
@Component
@RequiredArgsConstructor
public class CatalogStorefrontReadService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductMapper productMapper;

    /* ============================ Categories ============================ */

    @Transactional(readOnly = true)
    public List<CategoryView> categoriesFlat(String lang) {
        return categoryRepository.findAll().stream().filter(c -> c.getParent() == null)
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition)).map(c -> categoryView(c, lang, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryView> categoriesTree(String lang) {
        return categoryRepository.findAll().stream().filter(c -> c.getParent() == null)
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition)).map(c -> categoryView(c, lang, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryView categoryDetail(String idOrSlug, String lang) {
        return categoryView(resolveCategory(idOrSlug), lang, true);
    }

    @Transactional(readOnly = true)
    public List<CategoryView> categoryChildren(String idOrSlug, String lang) {
        UUID parentId = resolveCategory(idOrSlug).getId();
        return categoryRepository.findByParent_IdOrderByPositionAsc(parentId).stream()
                .map(c -> categoryView(c, lang, false)).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryBreadcrumb> categoryBreadcrumb(String idOrSlug, String lang) {
        List<CategoryBreadcrumb> out = new ArrayList<>();
        CategoryEntity c = resolveCategory(idOrSlug);
        while (c != null) {
            out.add(0, new CategoryBreadcrumb(c.getId(), c.getSlug(), translatedName(c, lang)));
            c = c.getParent();
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsByCategory(String idOrSlug, int page, int size, String lang,
            String sort) {
        UUID categoryId = resolveCategory(idOrSlug).getId();
        return productList(page, size, lang, null, categoryId, null, null, null, sort);
    }

    /* ============================ Suppliers ============================ */

    @Transactional(readOnly = true)
    public List<SupplierView> suppliers() {
        return supplierRepository.findAll().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::supplierView).toList();
    }

    @Transactional(readOnly = true)
    public SupplierView supplierDetail(UUID id) {
        return supplierView(supplierRepository.findById(id).orElseThrow(() -> new NotFoundException("Supplier")));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsBySupplier(UUID id, int page, int size, String lang, String sort) {
        return productList(page, size, lang, null, null, id, null, null, sort);
    }

    /* ============================ Variants ============================ */

    @Transactional(readOnly = true)
    public List<VariantView> variantsForProduct(UUID id) {
        return variantRepository.findByProductId(id).stream().filter(ProductVariantEntity::isActive)
                .map(this::variantView).toList();
    }

    @Transactional(readOnly = true)
    public VariantView variantById(UUID id) {
        return variantView(variantRepository.findById(id).orElseThrow(() -> new NotFoundException("Variant")));
    }

    @Transactional(readOnly = true)
    public VariantView variantBySku(UUID productId, String sku) {
        return variantRepository.findByProductId(productId).stream()
                .filter(v -> sku.equalsIgnoreCase(v.getSku()) || sku.equalsIgnoreCase(v.getExternalId())).findFirst()
                .map(this::variantView).orElseThrow(() -> new NotFoundException("Variant"));
    }

    /* ============================ Products listing ============================ */

    // Read-only tx keeps the Hibernate session open while mapping each product to a summary,
    // so the lazy `translations`/`images` collections load (otherwise LazyInitializationException).
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productListFull(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String shipFrom, Boolean freeShipping,
            Boolean selfPickup, Boolean hasVideo, Integer minRating, Integer inventoryMin, String certification,
            String sort) {

        int safeSize = Math.min(size, 100);
        Sort sortSpec = sortFor(sort);
        Pageable pageable = PageRequest.of(page, safeSize, sortSpec);

        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String shipCc = shipFrom == null ? null : shipFrom.toUpperCase();
        BigDecimal minRatingBd = minRating == null ? null : BigDecimal.valueOf(minRating);

        Page<ProductEntity> raw = productRepository.searchStorefront(ProductStatus.ACTIVE, needle, categoryId,
                supplierId, minPrice, maxPrice, shipCc, freeShipping, selfPickup, hasVideo, minRatingBd, inventoryMin,
                pageable);

        List<ProductEntity> filtered;
        if (certification != null && !certification.isBlank()) {
            String certUp = certification.toUpperCase();
            filtered = raw.getContent().stream().filter(p -> p.getCertifications() != null
                    && p.getCertifications().stream().anyMatch(c -> c != null && c.toUpperCase().contains(certUp)))
                    .toList();
        } else {
            filtered = raw.getContent();
        }

        List<ProductSummaryView> slice = filtered.stream().map(p -> productMapper.toSummary(p, lang)).toList();
        Page<ProductSummaryView> pageObj = new PageImpl<>(slice, pageable, raw.getTotalElements());
        return PageResponse.from(pageObj);
    }

    public PageResponse<ProductSummaryView> productList(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return productListFull(page, size, lang, q, categoryId, supplierId, minPrice, maxPrice, null, null, null, null,
                null, null, null, sort);
    }

    /* ============================ helpers ============================ */

    public CategoryEntity resolveCategory(String idOrSlug) {
        try {
            UUID uuid = UUID.fromString(idOrSlug);
            return categoryRepository.findById(uuid).orElseThrow(() -> new NotFoundException("Category"));
        } catch (IllegalArgumentException notUuid) {
            return categoryRepository.findBySlug(idOrSlug).orElseThrow(() -> new NotFoundException("Category"));
        }
    }

    public CategoryView categoryView(CategoryEntity c, String lang, boolean withChildren) {
        List<CategoryView> children = withChildren
                ? categoryRepository.findByParent_IdOrderByPositionAsc(c.getId()).stream()
                        .map(child -> categoryView(child, lang, true)).toList()
                : List.of();
        long count = productRepository.findAll().stream()
                .filter(p -> p.getCategory() != null && c.getId().equals(p.getCategory().getId())).count();
        return new CategoryView(c.getId(), c.getSlug(), translatedName(c, lang), c.getNameZh(),
                c.getParent() != null ? c.getParent().getId() : null, c.getPosition(), c.getIcon(), (int) count,
                children);
    }

    public SupplierView supplierView(SupplierEntity s) {
        long count = productRepository.findAll().stream()
                .filter(p -> p.getSupplier() != null && s.getId().equals(p.getSupplier().getId())).count();
        return new SupplierView(s.getId(), s.getExternalId(), s.getName(), s.getNameZh(), s.getCountry(), s.getCity(),
                s.getRating(), s.getYearsActive(), s.isVerified(), s.isTrustPass(), count);
    }

    public VariantView variantView(ProductVariantEntity v) {
        String img = v.getImageCdnUrl() != null ? v.getImageCdnUrl() : v.getImageSourceUrl();
        return new VariantView(v.getId(), v.getSku(), v.getExternalId(), v.getTitle(), v.getPrice(), v.getStock(), img,
                v.getOptions() != null ? v.getOptions() : Map.of(), v.isActive());
    }

    public ProductSummaryView summaryFor(ProductEntity p, String lang) {
        return productMapper.toSummary(p, lang);
    }

    public Sort sortFor(String sort) {
        return switch (sort == null ? "best_match" : sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "basePrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "sales", "lists" -> Sort.by(Sort.Direction.DESC, "monthlySales");
            case "rating" -> Sort.by(Sort.Direction.DESC, "rating");
            case "inventory" -> Sort.by(Sort.Direction.DESC, "inventoryCount");
            default -> Sort.by(Sort.Direction.DESC, "trendScore");
        };
    }

    public static String translatedName(CategoryEntity c, String lang) {
        return c.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage()))
                .map(CategoryTranslationEntity::getName).findFirst().orElse(c.getNameZh());
    }
}
