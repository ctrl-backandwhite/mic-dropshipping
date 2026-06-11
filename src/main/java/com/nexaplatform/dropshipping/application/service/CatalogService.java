package com.nexaplatform.dropshipping.application.service;

import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.messaging.ImageMirrorEvent;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final Slugify SLUG = Slugify.builder().lowerCase(true).build();

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CategoryRepository categoryRepository;
    private final ProductPriceTierRepository priceTierRepository;
    private final ProductImageRepository imageRepository;
    private final ProductMapper productMapper;
    private final CatalogStorefrontMapper catalogStorefrontMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /* ============ Suppliers ============ */

    @Transactional
    public SupplierEntity upsertSupplier(IngestSupplierRequest req) {
        SupplierEntity entity = supplierRepository.findBySourceAndExternalId(req.source(), req.externalId())
                .orElseGet(() -> SupplierEntity.builder()
                        .source(req.source())
                        .externalId(req.externalId())
                        .build());
        entity.setName(req.name());
        entity.setNameZh(req.nameZh());
        entity.setCountry(req.country() != null ? req.country() : "CN");
        entity.setCity(req.city());
        entity.setRating(req.rating());
        entity.setYearsActive(req.yearsActive());
        entity.setVerified(req.verified());
        entity.setTrustPass(req.trustPass());
        entity.setProfileUrl(req.profileUrl());
        return supplierRepository.save(entity);
    }

    /* ============ Categories ============ */

    /**
     * Admin variant of {@link #upsertCategory(IngestCategoryRequest)} that rejects
     * a duplicate slug with a 409 before creating a brand-new category. Moved out of
     * {@code AdminCatalogController} so the controller carries no business logic.
     */
    @Transactional
    public CategoryEntity createCategoryRejectingDuplicateSlug(IngestCategoryRequest req) {
        if (categoryRepository.findBySlug(req.slug()).isPresent()) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException(
                    "Ya existe una categoría con slug \"" + req.slug() + "\"");
        }
        return upsertCategory(req);
    }

    @Transactional
    public CategoryEntity upsertCategory(IngestCategoryRequest req) {
        CategoryEntity entity = categoryRepository.findBySlug(req.slug())
                .orElseGet(() -> CategoryEntity.builder()
                        .slug(req.slug())
                        .active(true)
                        .build());
        entity.setNameZh(req.nameZh());
        entity.setSource(req.source() != null ? req.source() : "1688");
        entity.setExternalId(req.externalId());
        entity.setPosition(req.position());
        entity.setIcon(req.icon());
        if (req.parentId() != null) {
            entity.setParent(categoryRepository.findById(req.parentId()).orElse(null));
        }
        entity = categoryRepository.save(entity);
        if (req.nameTranslations() != null) {
            for (Map.Entry<String, String> e : req.nameTranslations().entrySet()) {
                upsertCategoryTranslation(entity, e.getKey(), e.getValue());
            }
        }
        return entity;
    }

    private void upsertCategoryTranslation(CategoryEntity cat, String lang, String name) {
        Optional<CategoryTranslationEntity> existing = cat.getTranslations().stream()
                .filter(t -> t.getLanguage().equalsIgnoreCase(lang))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setName(name);
        } else {
            CategoryTranslationEntity tr = CategoryTranslationEntity.builder()
                    .category(cat)
                    .language(lang)
                    .name(name)
                    .build();
            cat.getTranslations().add(tr);
        }
    }

    /* ============ Products ============ */

    @Transactional
    public ProductEntity upsertProduct(IngestProductRequest req) {
        ProductEntity product = productRepository.findBySourceAndExternalId(req.source(), req.externalId())
                .orElseGet(() -> ProductEntity.builder()
                        .source(req.source())
                        .externalId(req.externalId())
                        .status(ProductStatus.DRAFT)
                        .moq(req.moq() != null ? req.moq() : 1)
                        .build());

        product.setTitleZh(req.titleZh());
        product.setShortDescriptionZh(req.shortDescriptionZh());
        product.setDescriptionZh(req.descriptionZh());
        product.setBrand(req.brand());
        product.setMoq(req.moq() != null ? req.moq() : 1);
        product.setBasePrice(req.basePrice());
        product.setCurrency(req.currency() != null ? req.currency() : "CNY");
        product.setWeightGrams(req.weightGrams());
        product.setMonthlySales(req.monthlySales() != null ? req.monthlySales() : 0);
        product.setRepurchaseRate(req.repurchaseRate());
        product.setRating(req.rating());
        product.setReviewCount(req.reviewCount() != null ? req.reviewCount() : 0);
        product.setSourceUrl(req.sourceUrl());
        product.setIngestedAt(Instant.now());
        product.setLastSyncedAt(Instant.now());

        if (product.getSlug() == null) {
            product.setSlug(buildSlug(req.titleZh(), req.externalId()));
        }

        if (req.supplierId() != null) {
            supplierRepository.findById(req.supplierId()).ifPresent(product::setSupplier);
        }
        if (req.categoryId() != null) {
            categoryRepository.findById(req.categoryId()).ifPresent(product::setCategory);
        }

        // Replace images
        product.getImages().clear();
        if (req.images() != null) {
            for (int i = 0; i < req.images().size(); i++) {
                var img = req.images().get(i);
                product.getImages().add(ProductImageEntity.builder()
                        .product(product)
                        .position(img.position())
                        .role(img.role() != null ? img.role() : "GALLERY")
                        .sourceUrl(img.sourceUrl())
                        .mirrorStatus(MirrorStatus.PENDING)
                        .build());
            }
        }

        // Replace variant options & values
        product.getVariantOptions().clear();
        if (req.options() != null) {
            for (IngestVariantOption optReq : req.options()) {
                VariantOptionEntity opt = VariantOptionEntity.builder()
                        .product(product)
                        .nameZh(optReq.nameZh())
                        .position(optReq.position())
                        .build();
                if (optReq.values() != null) {
                    for (var v : optReq.values()) {
                        opt.getValues().add(VariantValueEntity.builder()
                                .option(opt)
                                .valueZh(v.valueZh())
                                .position(v.position())
                                .imageSourceUrl(v.imageSourceUrl())
                                .build());
                    }
                }
                product.getVariantOptions().add(opt);
            }
        }

        // Replace variants
        product.getVariants().clear();
        if (req.variants() != null) {
            for (var v : req.variants()) {
                product.getVariants().add(ProductVariantEntity.builder()
                        .product(product)
                        .externalId(v.externalId())
                        .sku(v.sku())
                        .title(v.title())
                        .price(v.price())
                        .stock(v.stock() != null ? v.stock() : 0)
                        .imageSourceUrl(v.imageSourceUrl())
                        .options(v.options())
                        .active(true)
                        .build());
            }
        }

        product = productRepository.save(product);

        // Price tiers separately
        if (req.priceTiers() != null) {
            priceTierRepository.findByProductIdOrderByMinQtyAsc(product.getId())
                    .forEach(priceTierRepository::delete);
            for (var t : req.priceTiers()) {
                priceTierRepository.save(ProductPriceTierEntity.builder()
                        .product(product)
                        .minQty(t.minQty())
                        .maxQty(t.maxQty())
                        .unitPrice(t.unitPrice())
                        .currency(t.currency() != null ? t.currency() : "CNY")
                        .build());
            }
        }

        // Emit events. Guard against null ids — JPA assigns them at flush; in tests with
        // pure mocks they may be absent, in which case we silently skip to avoid NPEs.
        if (product.getId() != null) {
            kafkaTemplate.send(NexaTopics.PRODUCT_INGESTED, product.getId().toString(),
                    new ProductIngestedEvent(product.getId(), product.getSlug(), product.getSource(), product.getExternalId()));
            for (ProductImageEntity img : product.getImages()) {
                if (img.getMirrorStatus() == MirrorStatus.PENDING && img.getId() != null) {
                    kafkaTemplate.send(NexaTopics.IMAGE_FETCH, img.getId().toString(),
                            new ImageMirrorEvent(img.getId(), product.getId(), img.getSourceUrl(), "PRODUCT", img.getPosition()));
                }
            }
        }

        log.info("Upserted product {} ({} - {})", product.getId(), product.getSource(), product.getExternalId());
        return product;
    }

    /**
     * Admin product listing. Holds the paging + status-parsing logic that used to
     * live in {@code AdminCatalogController}: caps the page size and tolerates
     * 'ALL'/''/'undefined'/'null' and invalid status strings (full listing then).
     */
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProductsForAdmin(String status, int page, int size, String language) {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));
        // DROP-453: tolerate 'ALL', '', 'undefined' (axios sometimes serializes
        // undefined as a string) and invalid values — full listing in those cases.
        ProductStatus s = null;
        if (status != null && !status.isBlank()
                && !"ALL".equalsIgnoreCase(status)
                && !"undefined".equalsIgnoreCase(status)
                && !"null".equalsIgnoreCase(status)) {
            try {
                s = ProductStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                s = null;
            }
        }
        return listProducts(s, pageable, language);
    }

    /**
     * Updates a product status from its string representation. Holds the parsing
     * logic that used to live in {@code AdminCatalogController}.
     */
    @Transactional
    public void updateStatus(UUID id, String status) {
        updateStatus(id, ProductStatus.valueOf(status.toUpperCase()));
    }

    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language) {
        Page<ProductEntity> page = (status == null)
                ? productRepository.findAll(pageable)
                : productRepository.findByStatus(status, pageable);
        return page.map(p -> productMapper.toSummary(p, language));
    }

    /** Exposes the language-aware summary projection so controllers can render filtered slices. */
    public ProductSummaryView toSummaryView(ProductEntity p, String language) {
        return productMapper.toSummary(p, language);
    }

    /** Storefront: ordered image projections for a product. */
    @Transactional(readOnly = true)
    public List<CatalogImageDtoOut> listProductImages(UUID productId) {
        return catalogStorefrontMapper.toImageDtos(
                imageRepository.findByProductIdOrderByPositionAsc(productId));
    }

    /** Storefront: ordered price-tier projections for a product. */
    @Transactional(readOnly = true)
    public List<CatalogPriceTierDtoOut> listProductPriceTiers(UUID productId) {
        return catalogStorefrontMapper.toPriceTierDtos(
                priceTierRepository.findByProductIdOrderByMinQtyAsc(productId));
    }

    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listBestsellers(UUID categoryId, Pageable pageable, String language) {
        Page<ProductEntity> page = categoryId == null
                ? productRepository.findTopByTrendScore(ProductStatus.ACTIVE, pageable)
                : productRepository.findByCategoryOrderByTrend(categoryId, ProductStatus.ACTIVE, pageable);
        return page.map(p -> productMapper.toSummary(p, language));
    }

    // Plan 300k: PDP cacheado por (slug, lang, currency). Invalidamos en
    // quickEdit/updateStatus/duplicate desde el mismo servicio.
    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(
            value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL,
            key = "#slug + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
    public ProductDetailView getProductBySlug(String slug, String language) {
        ProductEntity p = productRepository.findWithDetailsBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Product not found: " + slug));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(
            value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL,
            key = "'id:' + #id + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
    public ProductDetailView getProductById(UUID id, String language) {
        ProductEntity p = productRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    /** Trigger lazy collections while still inside the transaction (open-in-view=false). */
    private void forceLoadCollections(ProductEntity p) {
        p.getImages().size();
        p.getVariants().size();
        p.getVariantOptions().forEach(o -> o.getValues().size());
        p.getTranslations().size();
    }

    // Plan 300k: cualquier mutación de producto desaloja TODO el namespace de
    // pdp/summary — más simple que computar la key exacta por idioma+divisa
    // y a 5 min de TTL el coste de un re-warm es despreciable.
    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL,  allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY, allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT,  allEntries = true)
    })
    public ProductEntity updateStatus(UUID id, ProductStatus status) {
        ProductEntity p = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        p.setStatus(status);
        return productRepository.save(p);
    }

    /** DROP-499: edición rápida desde /admin/catalog/{id} — sólo los campos editables a "golpe rápido". */
    @Transactional
    @org.springframework.cache.annotation.Caching(evict = {
        @org.springframework.cache.annotation.CacheEvict(value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL,  allEntries = true),
        @org.springframework.cache.annotation.CacheEvict(value = com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY, allEntries = true)
    })
    public ProductDetailView quickEdit(UUID id,
            com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn req,
            String lang) {
        ProductEntity p = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        if (req.getBrand()        != null) p.setBrand(req.getBrand());
        if (req.getBasePrice()    != null) p.setBasePrice(req.getBasePrice());
        if (req.getCurrency()     != null && !req.getCurrency().isBlank()) p.setCurrency(req.getCurrency());
        if (req.getMoq()          != null) p.setMoq(req.getMoq());
        if (req.getTitle() != null && !req.getTitle().isBlank()) {
            // Update the active language translation, not the canonical title_zh.
            var trOpt = p.getTranslations().stream()
                    .filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst();
            if (trOpt.isPresent()) {
                trOpt.get().setTitle(req.getTitle());
                if (req.getShortDescription() != null) trOpt.get().setShortDescription(req.getShortDescription());
            } else {
                p.getTranslations().add(
                    com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity.builder()
                        .product(p).language(lang).title(req.getTitle())
                        .shortDescription(req.getShortDescription())
                        .provider("admin").build());
            }
        }
        productRepository.save(p);
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    /** DROP-499: duplica un producto (mismo supplier/categoría) con nuevo externalId y slug.
     *  Copia básica: campos planos + traducciones (no clona imágenes ni variantes). */
    @Transactional
    public ProductDetailView duplicateProduct(UUID id, String lang) {
        ProductEntity src = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        String newExt = src.getExternalId() + "-COPY-" + System.currentTimeMillis() % 100000;
        ProductEntity copy = ProductEntity.builder()
                .source(src.getSource())
                .externalId(newExt)
                .titleZh(src.getTitleZh() + " (copy)")
                .shortDescriptionZh(src.getShortDescriptionZh())
                .descriptionZh(src.getDescriptionZh())
                .brand(src.getBrand())
                .moq(src.getMoq())
                .basePrice(src.getBasePrice())
                .currency(src.getCurrency())
                .supplier(src.getSupplier())
                .category(src.getCategory())
                .status(ProductStatus.DRAFT)
                .slug(buildSlug(src.getTitleZh(), newExt))
                .build();
        ProductEntity saved = productRepository.save(copy);
        // Copia traducciones existentes
        for (var tr : src.getTranslations()) {
            saved.getTranslations().add(
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity.builder()
                    .product(saved).language(tr.getLanguage())
                    .title((tr.getTitle() != null ? tr.getTitle() : "") + " (copy)")
                    .shortDescription(tr.getShortDescription())
                    .description(tr.getDescription())
                    .provider("admin-duplicate")
                    .build());
        }
        productRepository.save(saved);
        return productMapper.toDetail(saved, lang, java.util.Collections.emptyList());
    }

    /**
     * Trend-score = 0.4 * normalized monthly_sales + 0.3 * rating + 0.2 * repurchase_rate + 0.1 * review_count
     * Normalized scales: monthly_sales/1000, rating/5, repurchase_rate/100, review_count/500.
     */
    public BigDecimal computeTrendScore(ProductEntity p) {
        double salesNorm = Math.min(1.0, (p.getMonthlySales()) / 1000.0);
        double rating = p.getRating() != null ? p.getRating().doubleValue() / 5.0 : 0.0;
        double repurchase = p.getRepurchaseRate() != null ? p.getRepurchaseRate().doubleValue() / 100.0 : 0.0;
        double reviews = Math.min(1.0, p.getReviewCount() / 500.0);
        double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
        return BigDecimal.valueOf(score).setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    private String buildSlug(String title, String externalId) {
        String base = title == null ? "product" : title;
        String slug = SLUG.slugify(base);
        if (slug.length() > 100) slug = slug.substring(0, 100);
        return slug + "-" + externalId.toLowerCase();
    }
}
