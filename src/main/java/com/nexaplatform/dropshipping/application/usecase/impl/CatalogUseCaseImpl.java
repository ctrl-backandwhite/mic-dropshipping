package com.nexaplatform.dropshipping.application.usecase.impl;

import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.repository.ProductRepository;
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
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY;

/**
 * Catalog use case. Holds the logic that used to live in {@code CatalogService}
 * and the catalog controllers: supplier/category/product ingest, admin listing
 * (paging + status tolerance), the PDP/summary read projections (priced through
 * {@link ProductMapper}) and the admin mutations (status, quick-edit, duplicate).
 * Mutations go through the {@link ProductRepository} domain port operating on the
 * {@link Product} model; the legacy collaborators are kept for the ingest upsert
 * flow (managed supplier/category, separate price-tier table, Kafka events) and
 * the live-pricing read projections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogUseCaseImpl implements CatalogUseCase {

    private static final Slugify SLUG = Slugify.builder().lowerCase(true).build();

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CategoryRepository categoryRepository;
    private final ProductPriceTierRepository priceTierRepository;
    private final ProductImageRepository imageRepository;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository productJpaRepository;
    private final ProductMapper productMapper;
    private final CatalogStorefrontMapper catalogStorefrontMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository variantRepository;
    private final com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer productIndexer;
    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;

    // @Lazy field injection breaks the CatalogUseCaseImpl <-> CatalogFillWriter constructor cycle
    // (the writer ingests through this same use case).
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.nexaplatform.dropshipping.infrastructure.seed.CatalogFillWriter catalogFillWriter;

    /* ============ Suppliers ============ */

    @Override
    @Transactional
    public SupplierEntity upsertSupplier(IngestSupplierRequest req) {
        SupplierEntity entity = supplierRepository.findBySourceAndExternalId(req.source(), req.externalId())
                .orElseGet(() -> SupplierEntity.builder().source(req.source()).externalId(req.externalId()).build());
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

    @Override
    @Transactional
    public CategoryEntity createCategoryRejectingDuplicateSlug(IngestCategoryRequest req) {
        if (categoryRepository.findBySlug(req.slug()).isPresent()) {
            throw new BusinessException("Ya existe una categoría con slug \"" + req.slug() + "\"");
        }
        return upsertCategory(req);
    }

    @Override
    @Transactional
    public CategoryEntity upsertCategory(IngestCategoryRequest req) {
        CategoryEntity entity = categoryRepository.findBySlug(req.slug())
                .orElseGet(() -> CategoryEntity.builder().slug(req.slug()).active(true).build());
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
                .filter(t -> t.getLanguage().equalsIgnoreCase(lang)).findFirst();
        if (existing.isPresent()) {
            existing.get().setName(name);
        } else {
            cat.getTranslations()
                    .add(CategoryTranslationEntity.builder().category(cat).language(lang).name(name).build());
        }
    }

    /* ============ Products ============ */

    @Override
    @Transactional
    public ProductEntity upsertProduct(IngestProductRequest req) {
        ProductEntity product = productJpaRepository.findBySourceAndExternalId(req.source(), req.externalId())
                .orElseGet(() -> ProductEntity.builder().source(req.source()).externalId(req.externalId())
                        .status(ProductStatus.DRAFT).moq(req.moq() != null ? req.moq() : 1).build());

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
                product.getImages()
                        .add(ProductImageEntity.builder().product(product).position(img.position())
                                .role(img.role() != null ? img.role() : "GALLERY").sourceUrl(img.sourceUrl())
                                .mirrorStatus(MirrorStatus.PENDING).build());
            }
        }

        // DROP variant-images: a variant/value with no image of its own falls back to the
        // product's main image, so every variant always shows a coherent picture.
        final String mainImageUrl = mainImageUrlOf(req.images());

        // Replace variant options & values
        product.getVariantOptions().clear();
        if (req.options() != null) {
            for (IngestVariantOption optReq : req.options()) {
                VariantOptionEntity opt = VariantOptionEntity.builder().product(product).nameZh(optReq.nameZh())
                        .position(optReq.position()).build();
                if (optReq.values() != null) {
                    for (var v : optReq.values()) {
                        opt.getValues().add(VariantValueEntity.builder().option(opt).valueZh(v.valueZh())
                                .position(v.position())
                                .imageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl).build());
                    }
                }
                product.getVariantOptions().add(opt);
            }
        }

        // Replace variants
        product.getVariants().clear();
        if (req.variants() != null) {
            for (var v : req.variants()) {
                product.getVariants()
                        .add(ProductVariantEntity.builder().product(product).externalId(v.externalId()).sku(v.sku())
                                .title(v.title()).price(v.price()).stock(v.stock() != null ? v.stock() : 0)
                                .imageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl)
                                .options(v.options()).active(true).build());
            }
        }

        product = productJpaRepository.save(product);

        // Price tiers separately
        if (req.priceTiers() != null) {
            priceTierRepository.findByProductIdOrderByMinQtyAsc(product.getId()).forEach(priceTierRepository::delete);
            for (var t : req.priceTiers()) {
                priceTierRepository.save(ProductPriceTierEntity.builder().product(product).minQty(t.minQty())
                        .maxQty(t.maxQty()).unitPrice(t.unitPrice())
                        .currency(t.currency() != null ? t.currency() : "CNY").build());
            }
        }

        // Emit events. Guard against null ids — JPA assigns them at flush; in tests with
        // pure mocks they may be absent, in which case we silently skip to avoid NPEs.
        if (product.getId() != null) {
            kafkaTemplate.send(NexaTopics.PRODUCT_INGESTED, product.getId().toString(), new ProductIngestedEvent(
                    product.getId(), product.getSlug(), product.getSource(), product.getExternalId()));
            for (ProductImageEntity img : product.getImages()) {
                if (img.getMirrorStatus() == MirrorStatus.PENDING && img.getId() != null) {
                    kafkaTemplate.send(NexaTopics.IMAGE_FETCH, img.getId().toString(), new ImageMirrorEvent(img.getId(),
                            product.getId(), img.getSourceUrl(), "PRODUCT", img.getPosition()));
                }
            }
        }

        log.info("Upserted product {} ({} - {})", product.getId(), product.getSource(), product.getExternalId());
        return product;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProductsForAdmin(String status, UUID categoryId, int page, int size,
            String language) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        ProductStatus st = parseStatusTolerant(status);
        if (categoryId == null) {
            return listProducts(st, pageable, language);
        }
        Page<ProductEntity> entities = (st == null)
                ? productJpaRepository.findByCategoryId(categoryId, pageable)
                : productJpaRepository.findByCategoryIdAndStatus(categoryId, st, pageable);
        return entities.map(p -> productMapper.toSummary(p, language));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language) {
        Page<ProductEntity> page = (status == null)
                ? productJpaRepository.findAll(pageable)
                : productJpaRepository.findByStatus(status, pageable);
        return page.map(p -> productMapper.toSummary(p, language));
    }

    @Override
    public ProductSummaryView toSummaryView(Product product, String language) {
        ProductEntity entity = product == null || product.getId() == null
                ? null
                : productJpaRepository.findById(product.getId()).orElse(null);
        return entity == null ? null : productMapper.toSummary(entity, language);
    }

    @Override
    @Transactional(readOnly = true)
    public Product getProductModelById(UUID id) {
        Product model = productRepository.getById(id);
        if (model == null) {
            throw new NotFoundException("Product not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional(readOnly = true)
    public Product getProductModelBySlug(String slug) {
        return productRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Product not found: " + slug));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogImageDtoOut> listProductImages(UUID productId) {
        return catalogStorefrontMapper.toImageDtos(imageRepository.findByProductIdOrderByPositionAsc(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CatalogPriceTierDtoOut> listProductPriceTiers(UUID productId) {
        return catalogStorefrontMapper.toPriceTierDtos(priceTierRepository.findByProductIdOrderByMinQtyAsc(productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listBestsellers(UUID categoryId, Pageable pageable, String language) {
        Page<ProductEntity> page = categoryId == null
                ? productJpaRepository.findTopByTrendScore(ProductStatus.ACTIVE, pageable)
                : productJpaRepository.findByCategoryOrderByTrend(categoryId, ProductStatus.ACTIVE, pageable);
        return page.map(p -> productMapper.toSummary(p, language));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "#slug + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
    public ProductDetailView getProductBySlug(String slug, String language) {
        ProductEntity p = productJpaRepository.findWithDetailsBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Product not found: " + slug));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "'id:' + #id + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
    public ProductDetailView getProductById(UUID id, String language) {
        ProductEntity p = productJpaRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDetailView getProductByExternal(String source, String externalId, String language) {
        ProductEntity p = productJpaRepository.findBySourceAndExternalId(source, externalId)
                .orElseThrow(() -> new NotFoundException("Product"));
        return getProductById(p.getId(), language);
    }

    /** Trigger lazy collections while still inside the transaction (open-in-view=false). */
    private void forceLoadCollections(ProductEntity p) {
        p.getImages().size();
        p.getVariants().size();
        p.getVariantOptions().forEach(o -> o.getValues().size());
        p.getTranslations().size();
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, String status) {
        updateStatus(id, ProductStatus.valueOf(status.toUpperCase()));
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void updateStatus(UUID id, ProductStatus status) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        p.setStatus(status);
        productJpaRepository.save(p);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        if (req.getBrand() != null)
            p.setBrand(req.getBrand());
        if (req.getBasePrice() != null)
            p.setBasePrice(req.getBasePrice());
        if (req.getCurrency() != null && !req.getCurrency().isBlank())
            p.setCurrency(req.getCurrency());
        if (req.getMoq() != null)
            p.setMoq(req.getMoq());
        boolean touchesTranslation = (req.getTitle() != null && !req.getTitle().isBlank())
                || req.getShortDescription() != null || req.getDescription() != null;
        if (touchesTranslation) {
            // Update the active-language translation (title/short/long description), not the canonical title_zh.
            var trOpt = p.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst();
            var tr = trOpt.orElseGet(() -> {
                var n = com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity
                        .builder().product(p).language(lang).provider("admin").build();
                p.getTranslations().add(n);
                return n;
            });
            if (req.getTitle() != null && !req.getTitle().isBlank())
                tr.setTitle(req.getTitle());
            if (req.getShortDescription() != null)
                tr.setShortDescription(req.getShortDescription());
            if (req.getDescription() != null)
                tr.setDescription(req.getDescription());
        }
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional
    public ProductDetailView duplicateProduct(UUID id, String lang) {
        ProductEntity src = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        String newExt = src.getExternalId() + "-COPY-" + System.currentTimeMillis() % 100000;
        ProductEntity copy = ProductEntity.builder().source(src.getSource()).externalId(newExt)
                .titleZh(src.getTitleZh() + " (copy)").shortDescriptionZh(src.getShortDescriptionZh())
                .descriptionZh(src.getDescriptionZh()).brand(src.getBrand()).moq(src.getMoq())
                .basePrice(src.getBasePrice()).currency(src.getCurrency()).supplier(src.getSupplier())
                .category(src.getCategory()).status(ProductStatus.DRAFT).slug(buildSlug(src.getTitleZh(), newExt))
                .build();
        ProductEntity saved = productJpaRepository.save(copy);
        for (var tr : src.getTranslations()) {
            saved.getTranslations()
                    .add(com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity
                            .builder().product(saved).language(tr.getLanguage())
                            .title((tr.getTitle() != null ? tr.getTitle() : "") + " (copy)")
                            .shortDescription(tr.getShortDescription()).description(tr.getDescription())
                            .provider("admin-duplicate").build());
        }
        productJpaRepository.save(saved);
        return productMapper.toDetail(saved, lang, java.util.Collections.emptyList());
    }

    @Override
    public java.math.BigDecimal computeTrendScore(ProductEntity p) {
        double salesNorm = Math.min(1.0, (p.getMonthlySales()) / 1000.0);
        double rating = p.getRating() != null ? p.getRating().doubleValue() / 5.0 : 0.0;
        double repurchase = p.getRepurchaseRate() != null ? p.getRepurchaseRate().doubleValue() / 100.0 : 0.0;
        double reviews = Math.min(1.0, p.getReviewCount() / 500.0);
        double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
        return java.math.BigDecimal.valueOf(score).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /* ============ helpers ============ */

    /**
     * Tolerates 'ALL', '', 'undefined' (axios sometimes serializes undefined as a
     * string), 'null' and invalid status strings — full listing in those cases.
     */
    private ProductStatus parseStatusTolerant(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) || "undefined".equalsIgnoreCase(status)
                || "null".equalsIgnoreCase(status)) {
            return null;
        }
        try {
            return ProductStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String buildSlug(String title, String externalId) {
        String base = title == null ? "product" : title;
        String slug = SLUG.slugify(base);
        if (slug.length() > 100)
            slug = slug.substring(0, 100);
        return slug + "-" + externalId.toLowerCase();
    }

    /* ============ Reindex (admin) ============ */

    @Override
    public int reindexAllProducts() {
        return productIndexer.reindexAll();
    }

    /* ============ Variants (admin CRUD) ============ */

    @Override
    @Transactional(readOnly = true)
    public List<VariantView> listVariantsForAdmin(UUID productId) {
        return variantRepository.findByProductId(productId).stream().map(productMapper::toVariantView).toList();
    }

    @Override
    @Transactional
    // DROP-639: also evict the pricing-amount cache so the product's headline (Resumen) price
    // recomputes from the new variant — otherwise it shows the stale pre-edit value.
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public VariantView createVariant(UUID productId, AdminVariantUpsertDtoIn req) {
        ProductEntity product = productJpaRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        ProductVariantEntity v = new ProductVariantEntity();
        v.setProduct(product);
        applyVariant(v, req);
        v.setExternalId(req.getSku());
        ProductVariantEntity saved = variantRepository.save(v);
        productIndexer.indexProduct(productId);
        return productMapper.toVariantView(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public VariantView updateVariant(UUID variantId, AdminVariantUpsertDtoIn req) {
        ProductVariantEntity v = variantRepository.findById(variantId)
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        applyVariant(v, req);
        ProductVariantEntity saved = variantRepository.save(v);
        productIndexer.indexProduct(v.getProduct().getId());
        return productMapper.toVariantView(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void deleteVariant(UUID variantId) {
        ProductVariantEntity v = variantRepository.findById(variantId)
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        UUID productId = v.getProduct().getId();
        variantRepository.delete(v);
        productIndexer.indexProduct(productId);
    }

    /* ============ Images (upload + product gallery) ============ */

    @Override
    public String uploadImage(byte[] bytes, String contentType, String originalName) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("El archivo de imagen está vacío");
        }
        String ext = extensionFor(contentType, originalName);
        String key = "uploads/" + UUID.randomUUID() + ext;
        return storageService.putBytes(key, bytes, contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream");
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public ProductImageView addProductImage(UUID productId, String url,
            String role) {
        if (url == null || url.isBlank()) {
            throw new BusinessException("La URL de imagen es obligatoria");
        }
        ProductEntity product = productJpaRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        int nextPos = product.getImages().stream().mapToInt(ProductImageEntity::getPosition).max().orElse(-1) + 1;
        boolean asMain = product.getImages().isEmpty() || "MAIN".equalsIgnoreCase(role);
        if (asMain) {
            product.getImages().forEach(i -> {
                if ("MAIN".equalsIgnoreCase(i.getRole())) {
                    i.setRole("GALLERY");
                }
            });
        }
        // The URL is already reachable (uploaded to our storage or an external CDN), so expose it
        // directly as the cdnUrl and mark it MIRRORED — no async mirroring needed to display it.
        // Persist via the image repository so the generated id is returned (the product→images
        // collection is not cascade-persist). Role demotions above flush with the transaction.
        ProductImageEntity img = ProductImageEntity.builder().product(product).position(nextPos)
                .role(asMain ? "MAIN" : "GALLERY").sourceUrl(url).cdnUrl(url)
                .mirrorStatus(MirrorStatus.MIRRORED).build();
        ProductImageEntity saved = imageRepository.save(img);
        productIndexer.indexProduct(productId);
        return productMapper.toImageView(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public void deleteProductImage(UUID imageId) {
        ProductImageEntity img = imageRepository.findById(imageId)
                .orElseThrow(() -> new NotFoundException("Image not found"));
        UUID productId = img.getProduct() != null ? img.getProduct().getId() : null;
        imageRepository.delete(img);
        if (productId != null) {
            productIndexer.indexProduct(productId);
        }
    }

    private String extensionFor(String contentType, String originalName) {
        if (originalName != null && originalName.contains(".")) {
            String ext = originalName.substring(originalName.lastIndexOf('.')).toLowerCase();
            if (ext.matches("\\.[a-z0-9]{2,5}")) {
                return ext;
            }
        }
        if (contentType == null) {
            return "";
        }
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/avif" -> ".avif";
            default -> "";
        };
    }

    private void applyVariant(ProductVariantEntity v, AdminVariantUpsertDtoIn req) {
        v.setSku(req.getSku());
        v.setTitle(req.getTitle() != null && !req.getTitle().isBlank() ? req.getTitle() : req.getSku());
        v.setPrice(req.getPrice());
        v.setStock(req.getStock() != null ? req.getStock() : 0);
        v.setBarcode(req.getBarcode());
        // A variant without an explicit image inherits the product's main image so its
        // thumbnail is never empty. An explicit URL (typed or uploaded) always wins.
        String img = req.getImageUrl();
        if (img == null || img.isBlank()) {
            img = productMainImage(v.getProduct());
        }
        v.setImageSourceUrl(img);
        v.setOptions(req.getOptions() != null ? req.getOptions() : java.util.Map.of());
        v.setActive(req.getActive() == null || req.getActive());
    }

    /** Main image URL of an ingest payload (role MAIN first, then lowest position). */
    private String mainImageUrlOf(java.util.List<IngestImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream().filter(i -> i.sourceUrl() != null && !i.sourceUrl().isBlank())
                .min(java.util.Comparator
                        .comparingInt((IngestImage i) -> "MAIN"
                                .equalsIgnoreCase(i.role()) ? 0 : 1)
                        .thenComparingInt(IngestImage::position))
                .map(IngestImage::sourceUrl).orElse(null);
    }

    /** Main image (CDN preferred, else source) of a persisted product, or null. */
    private String productMainImage(ProductEntity p) {
        if (p == null || p.getImages() == null) {
            return null;
        }
        return p.getImages().stream()
                .filter(i -> (i.getCdnUrl() != null && !i.getCdnUrl().isBlank())
                        || (i.getSourceUrl() != null && !i.getSourceUrl().isBlank()))
                .min(java.util.Comparator
                        .comparingInt((ProductImageEntity i) -> "MAIN".equalsIgnoreCase(i.getRole()) ? 0 : 1)
                        .thenComparingInt(ProductImageEntity::getPosition))
                .map(i -> i.getCdnUrl() != null && !i.getCdnUrl().isBlank() ? i.getCdnUrl() : i.getSourceUrl())
                .orElse(null);
    }


    /* ============ Bulk import (admin) ============ */

    @Override
    public com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut bulkCreateProducts(
            java.util.List<com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn> rows) {
        int created = 0, failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<SupplierEntity> suppliers = supplierRepository.findAll();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            try {
                buildAndWriteProduct(r, suppliers);
                created++;
            } catch (Exception e) {
                failed++;
                errors.add("fila " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (created > 0)
            productIndexer.reindexAll();
        return new com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut(created, failed, errors);
    }

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public UUID createProductManual(com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn req) {
        UUID id = buildAndWriteProduct(req, supplierRepository.findAll());
        productIndexer.indexProduct(id);
        return id;
    }

    private static final List<String> PRODUCT_CHILD_TABLES = List.of("product_price_tier", "product_tag_link",
            "product_specification", "product_attribute", "product_keyword", "product_history", "product_review",
            "product_warehouse_stock", "product_sync_state", "bestseller_ranking", "category_ranking", "trend_signal",
            "shop_product_listing", "pod_design");

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public void deleteProduct(UUID id) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        Long orders = jdbcTemplate.queryForObject("SELECT count(*) FROM order_item WHERE product_id = ?", Long.class,
                id);
        if (orders != null && orders > 0) {
            throw new BusinessException("No se puede eliminar: el producto tiene pedidos. Archívalo en su lugar.");
        }
        // Flat children without JPA cascade are removed first; the mapped collections
        // (images/variants/translations/variantOptions) cascade on the entity delete.
        for (String table : PRODUCT_CHILD_TABLES) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", id);
        }
        productJpaRepository.delete(p);
        productIndexer.deleteFromIndex(id);
        log.info("::> [CATALOG] Product deleted id={}", id);
    }

    /** Builds the heavy ingest request from a friendly row, persists it (via the writer) and returns its id. */
    private UUID buildAndWriteProduct(com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn r,
            java.util.List<SupplierEntity> suppliers) {
        CategoryEntity cat = categoryRepository.findBySlug(r.getCategorySlug())
                .orElseThrow(() -> new BusinessException("Categoría no encontrada: " + r.getCategorySlug()));
        UUID supplierId = resolveBulkSupplier(suppliers, r.getSupplierExternalId());
        String esTitle = r.getTitleEs();
        String enTitle = (r.getTitleEn() != null && !r.getTitleEn().isBlank()) ? r.getTitleEn() : esTitle;
        String zhTitle = (r.getTitleZh() != null && !r.getTitleZh().isBlank()) ? r.getTitleZh() : esTitle;
        String esDesc = (r.getDescriptionEs() != null && !r.getDescriptionEs().isBlank()) ? r.getDescriptionEs()
                : esTitle;
        java.math.BigDecimal price = r.getPrice() != null ? r.getPrice() : new java.math.BigDecimal("9.90");
        String externalId = (r.getExternalId() != null && !r.getExternalId().isBlank()) ? r.getExternalId()
                : "BULK-" + SLUG.slugify(esTitle) + "-" + System.nanoTime();
        java.util.List<IngestImage> images = new java.util.ArrayList<>();
        if (r.getImageUrls() != null) {
            for (int k = 0; k < r.getImageUrls().size(); k++)
                images.add(new IngestImage(
                        r.getImageUrls().get(k), k, k == 0 ? "MAIN" : "GALLERY"));
        }
        var variant = new IngestVariant(
                externalId + "-DEF", externalId + "-DEF", esTitle, price, 100, null, java.util.Map.of());
        var tier = new IngestPriceTier(1, null, price, "CNY");
        var req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc, r.getManufacturer(),
                r.getMoq() != null ? r.getMoq() : 1, price, "CNY", null,
                r.getMonthlySales() != null ? r.getMonthlySales() : 0, new java.math.BigDecimal("15"),
                r.getRating() != null ? r.getRating() : new java.math.BigDecimal("4.5"), 0,
                "https://detail.1688.com/offer/" + externalId + ".html", supplierId, cat.getId(), images,
                java.util.List.<IngestVariantOption>of(), java.util.List.of(variant), java.util.List.of(tier));
        return catalogFillWriter.write(req, esTitle, enTitle, zhTitle, esDesc, esDesc);
    }

    @Override
    public com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut bulkCreateCategories(
            java.util.List<com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn> rows) {
        int created = 0, failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            try {
                int position = r.getPosition() != null ? r.getPosition() : (int) (categoryRepository.count() + 1);
                String pt = (r.getNamePt() != null && !r.getNamePt().isBlank()) ? r.getNamePt() : r.getNameEs();
                String en = (r.getNameEn() != null && !r.getNameEn().isBlank()) ? r.getNameEn() : r.getNameEs();
                String zh = (r.getNameZh() != null && !r.getNameZh().isBlank()) ? r.getNameZh() : r.getNameEs();
                // Resolve the optional parent by slug (a parent listed earlier in the batch is
                // already persisted, so it is visible here). An unknown slug fails just that row.
                UUID parentId = null;
                if (r.getParentSlug() != null && !r.getParentSlug().isBlank()) {
                    parentId = categoryRepository.findBySlug(r.getParentSlug()).map(CategoryEntity::getId)
                            .orElseThrow(() -> new BusinessException(
                                    "Categoría padre no encontrada: " + r.getParentSlug()));
                }
                upsertCategory(new IngestCategoryRequest(r.getSlug(), parentId, "1688", null, zh, position,
                        r.getIcon() != null ? r.getIcon() : "tag",
                        java.util.Map.of("es", r.getNameEs(), "en", en, "pt", pt)));
                created++;
            } catch (Exception e) {
                failed++;
                errors.add("fila " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut(created, failed, errors);
    }

    private UUID resolveBulkSupplier(java.util.List<SupplierEntity> suppliers, String supplierExternalId) {
        if (supplierExternalId != null && !supplierExternalId.isBlank()) {
            return supplierRepository.findBySourceAndExternalId("1688", supplierExternalId)
                    .map(SupplierEntity::getId)
                    .orElseThrow(() -> new BusinessException("Proveedor no encontrado: " + supplierExternalId));
        }
        if (suppliers.isEmpty())
            throw new BusinessException("No hay proveedores; crea uno antes de importar productos");
        return suppliers.get(0).getId();
    }

}
