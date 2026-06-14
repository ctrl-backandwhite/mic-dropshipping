package com.nexaplatform.dropshipping.application.usecase.impl;

import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
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
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
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
    private final com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer categoryIndexer;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductSpecificationRepository productSpecificationRepository;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository variantValueRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;

    // @Lazy field injection breaks the CatalogUseCaseImpl <-> CatalogFillWriter constructor cycle
    // (the writer ingests through this same use case).
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.nexaplatform.dropshipping.infrastructure.seed.CatalogFillWriter catalogFillWriter;

    // DROP-677: mapeo de categorías 1688 → interna (inyección por campo para no alterar el constructor).
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexaplatform.dropshipping.infrastructure.persistence.repository.Category1688MappingRepository category1688MappingRepository;

    // DROP-670: esquema de atributos por categoría (inyección por campo para no alterar el constructor).
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;

    // Reseñas reales en la carga masiva (inyección por campo para no alterar el constructor).
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

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
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    upsertCategoryTranslation(entity, e.getKey(), e.getValue());
                }
            }
            // Re-save so the cascade actually persists the translation rows (they are added to the
            // collection after the first save; without this they were silently dropped).
            entity = categoryRepository.save(entity);
        }
        categoryIndexer.indexCategory(entity.getId());
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
        p.getVariantOptions().forEach(o -> o.getValues().forEach(v -> v.getTranslations().size()));
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
        // DROP-679: al publicar se generan los metadatos SEO por idioma a partir del contenido real
        // (título/descripción ya traducidos), sin sobrescribir los que el operador haya definido.
        if (status == ProductStatus.ACTIVE) {
            generateSeoMetadata(p);
        }
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
    }

    /** DROP-679: rellena meta_title/meta_description (solo si están vacíos) desde el contenido real. */
    private void generateSeoMetadata(ProductEntity p) {
        for (var tr : p.getTranslations()) {
            String title = tr.getTitle() != null && !tr.getTitle().isBlank() ? tr.getTitle().trim() : null;
            if (title == null) {
                continue;
            }
            if (tr.getMetaTitle() == null || tr.getMetaTitle().isBlank()) {
                String mt = title;
                if (p.getBrand() != null && !p.getBrand().isBlank()
                        && !title.toLowerCase().contains(p.getBrand().toLowerCase())
                        && (mt.length() + p.getBrand().length() + 3) <= 65) {
                    mt = mt + " | " + p.getBrand().trim();
                }
                tr.setMetaTitle(mt.length() > 200 ? mt.substring(0, 200) : mt);
            }
            if (tr.getMetaDescription() == null || tr.getMetaDescription().isBlank()) {
                String base = tr.getShortDescription() != null && !tr.getShortDescription().isBlank()
                        ? tr.getShortDescription()
                        : (tr.getDescription() != null ? tr.getDescription() : title);
                String md = base.replaceAll("\\s+", " ").trim();
                if (md.length() > 155) {
                    md = md.substring(0, 152).trim() + "…";
                }
                tr.setMetaDescription(md);
            }
        }
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
        // DROP-673: edición del vídeo real del producto desde el admin (cadena vacía lo elimina).
        if (req.getVideoUrl() != null) {
            String vu = req.getVideoUrl().trim();
            p.setVideoUrl(vu.isEmpty() ? null : vu);
            p.setHasVideo(!vu.isEmpty() || (p.getVideoUrls() != null && !p.getVideoUrls().isEmpty()));
        }
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
        String full = slug + "-" + externalId.toLowerCase();
        // slug es varchar(220): se capa por seguridad ante títulos + externalId largos.
        return full.length() > 220 ? full.substring(0, 220) : full;
    }

    /* ============ Reindex (admin) ============ */

    @Override
    public int reindexAllProducts() {
        return productIndexer.reindexAll();
    }

    @Override
    @Transactional
    public int backfillVariantAxes() {
        int filled = 0;
        for (ProductEntity p : productJpaRepository.findAll()) {
            if (!p.getVariantOptions().isEmpty() || p.getVariants() == null || p.getVariants().isEmpty()) {
                continue;
            }
            java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> derived = new java.util.LinkedHashMap<>();
            for (var v : p.getVariants()) {
                if (v.getOptions() == null) {
                    continue;
                }
                for (var e : v.getOptions().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                        continue;
                    }
                    derived.computeIfAbsent(e.getKey().trim(), k -> new java.util.LinkedHashSet<>())
                            .add(e.getValue().trim());
                }
            }
            if (derived.isEmpty()) {
                continue;
            }
            int op = 0;
            for (var en : derived.entrySet()) {
                var opt = new com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity();
                opt.setProduct(p);
                opt.setNameZh(en.getKey());
                opt.setName(en.getKey());
                opt.setPosition(op++);
                int vp = 0;
                for (String val : en.getValue()) {
                    var vv = new com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity();
                    vv.setOption(opt);
                    vv.setValueZh(val);
                    vv.setPosition(vp++);
                    opt.getValues().add(vv);
                }
                p.getVariantOptions().add(opt);
            }
            productJpaRepository.save(p);
            productIndexer.indexProduct(p.getId());
            filled++;
        }
        if (filled > 0) {
            log.info("::> [VARIANTS] Backfilled variation axes from variants for {} products", filled);
        }
        return filled;
    }

    @Override
    @Transactional
    public int backfillMissingSeo() {
        int filled = 0;
        for (ProductEntity p : productJpaRepository.findAll()) {
            if (p.getStatus() != ProductStatus.ACTIVE) {
                continue;
            }
            boolean missing = p.getTranslations().stream().anyMatch(tr ->
                    tr.getTitle() != null && !tr.getTitle().isBlank()
                            && (tr.getMetaTitle() == null || tr.getMetaTitle().isBlank()
                                    || tr.getMetaDescription() == null || tr.getMetaDescription().isBlank()));
            if (missing) {
                generateSeoMetadata(p);
                productJpaRepository.save(p);
                filled++;
            }
        }
        if (filled > 0) {
            log.info("::> [SEO] Backfilled SEO metadata for {} active products", filled);
        }
        return filled;
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
    public VariantView updateVariantPrice(UUID variantId, java.math.BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new BusinessException("El precio de la variante debe ser ≥ 0");
        }
        ProductVariantEntity v = variantRepository.findById(variantId)
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        v.setPrice(price);
        ProductVariantEntity saved = variantRepository.save(v);
        productIndexer.indexProduct(v.getProduct().getId());
        return productMapper.toVariantView(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public void renameVariantValue(UUID valueId, String label) {
        var v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException("Variant value"));
        v.setValue(label != null && !label.isBlank() ? label.trim() : null);
        variantValueRepository.save(v);
        if (v.getOption() != null && v.getOption().getProduct() != null) {
            productIndexer.indexProduct(v.getOption().getProduct().getId());
        }
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public void setVariantValueImage(UUID valueId, String imageUrl) {
        var v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException("Variant value"));
        // DROP-674: imagen real por color. Una cadena vacía la elimina (volverá a usar la principal).
        String url = imageUrl != null ? imageUrl.trim() : null;
        v.setImageSourceUrl(url != null && !url.isEmpty() ? url : null);
        v.setImageCdnUrl(url != null && !url.isEmpty() ? url : null);
        variantValueRepository.save(v);
        if (v.getOption() != null && v.getOption().getProduct() != null) {
            productIndexer.indexProduct(v.getOption().getProduct().getId());
        }
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true)})
    public void setVariantValueTranslation(UUID valueId, String language, String value) {
        if (language == null || language.isBlank()) {
            throw new BusinessException("language es obligatorio");
        }
        var v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException("Variant value"));
        String lang = language.trim().toLowerCase();
        String val = value != null ? value.trim() : null;
        v.getTranslations().removeIf(tt -> lang.equalsIgnoreCase(tt.getLanguage()));
        if (val != null && !val.isEmpty()) {
            v.getTranslations().add(com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity
                    .builder().variantValue(v).language(lang).value(val).build());
        }
        variantValueRepository.save(v);
        if (v.getOption() != null && v.getOption().getProduct() != null) {
            productIndexer.indexProduct(v.getOption().getProduct().getId());
        }
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
        CategoryEntity cat = resolveBulkCategory(r);
        // DROP-670: si la categoría define atributos obligatorios, el producto debe traerlos (integridad).
        var requiredSchema = categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(cat.getId()).stream()
                .filter(com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity::isRequired)
                .map(com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity::getAttrKey)
                .toList();
        if (!requiredSchema.isEmpty()) {
            java.util.Set<String> provided = new java.util.HashSet<>();
            if (r.getAttributes() != null) {
                for (var a : r.getAttributes()) {
                    if (a.getKey() != null && a.getValue() != null && !a.getValue().isBlank()) {
                        provided.add(a.getKey().trim().toLowerCase());
                    }
                }
            }
            for (String key : requiredSchema) {
                if (!provided.contains(key.toLowerCase())) {
                    throw new BusinessException("Falta el atributo obligatorio '" + key + "' para la categoría "
                            + cat.getSlug());
                }
            }
        }
        // El proveedor se toma de supplierName; si no, del fabricante (manufacturer). Solo si no hay
        // ninguno se usa el primero por defecto.
        String supName = (r.getSupplierName() != null && !r.getSupplierName().isBlank()) ? r.getSupplierName()
                : r.getManufacturer();
        UUID supplierId = resolveBulkSupplier(suppliers, r.getSupplierExternalId(), supName);
        String esTitle = r.getTitleEs();
        String enTitle = (r.getTitleEn() != null && !r.getTitleEn().isBlank()) ? r.getTitleEn() : esTitle;
        String zhTitle = (r.getTitleZh() != null && !r.getTitleZh().isBlank()) ? r.getTitleZh() : esTitle;
        String esDesc = (r.getDescriptionEs() != null && !r.getDescriptionEs().isBlank()) ? r.getDescriptionEs()
                : esTitle;
        // DROP-680: el precio es dato real obligatorio; no se inventa. Si no viene explícito se toma
        // del tramo de precio más bajo (price break real); si tampoco hay tramos, se rechaza la fila.
        java.math.BigDecimal price = r.getPrice();
        if (price == null && r.getTieredPricing() != null) {
            for (var t : r.getTieredPricing()) {
                if (t.getUnitPrice() != null && (price == null || t.getUnitPrice().compareTo(price) < 0)) {
                    price = t.getUnitPrice();
                }
            }
        }
        if (price == null) {
            throw new BusinessException("Falta el precio real del producto (price o tieredPricing): " + esTitle);
        }
        // external_id es varchar(120): con títulos largos el slug autogenerado lo desbordaba. Se capa
        // el slug para que "BULK-<slug>-<nanoTime>" (y cualquier externalId provisto) quepa en 120.
        String externalId;
        if (r.getExternalId() != null && !r.getExternalId().isBlank()) {
            externalId = r.getExternalId().trim();
        } else {
            String base = SLUG.slugify(esTitle);
            if (base.length() > 90) {
                base = base.substring(0, 90);
            }
            externalId = "BULK-" + base + "-" + System.nanoTime();
        }
        if (externalId.length() > 120) {
            externalId = externalId.substring(0, 120);
        }
        java.util.List<IngestImage> images = new java.util.ArrayList<>();
        if (r.getImageUrls() != null) {
            for (int k = 0; k < r.getImageUrls().size(); k++)
                images.add(new IngestImage(
                        r.getImageUrls().get(k), k, k == 0 ? "MAIN" : "GALLERY"));
        }
        // Ejes de variación (Color/Talla) desde el JSON.
        java.util.List<IngestVariantOption> options = new java.util.ArrayList<>();
        if (r.getVariantAxes() != null) {
            for (var ax : r.getVariantAxes()) {
                if (ax.getName() == null || ax.getName().isBlank()) {
                    continue;
                }
                java.util.List<IngestVariantValue> vals = new java.util.ArrayList<>();
                if (ax.getValues() != null) {
                    int vp = 0;
                    for (String val : ax.getValues()) {
                        // DROP-674: imagen real por valor (p.ej. la foto del color), si el proveedor la trae.
                        String img = ax.getValueImages() != null ? ax.getValueImages().get(val) : null;
                        vals.add(new IngestVariantValue(val, vp++, img != null && !img.isBlank() ? img : null));
                    }
                }
                options.add(new IngestVariantOption(ax.getName(), options.size(), vals));
            }
        }
        // Si no se declararon ejes pero las variantes traen optionValues, se derivan los ejes y valores
        // automáticamente (cada clave -> un eje; sus valores distintos -> los valores), preservando el
        // orden. Así un import/alta que solo trae variantes (Color/Talla en optionValues) muestra el
        // selector en la ficha sin tener que repetir los ejes a mano.
        if (options.isEmpty() && r.getVariants() != null) {
            java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> derived = new java.util.LinkedHashMap<>();
            for (var v : r.getVariants()) {
                if (v.getOptionValues() == null) {
                    continue;
                }
                for (var e : v.getOptionValues().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                        continue;
                    }
                    derived.computeIfAbsent(e.getKey().trim(), k -> new java.util.LinkedHashSet<>())
                            .add(e.getValue().trim());
                }
            }
            for (var en : derived.entrySet()) {
                java.util.List<IngestVariantValue> vals = new java.util.ArrayList<>();
                int vp = 0;
                for (String val : en.getValue()) {
                    vals.add(new IngestVariantValue(val, vp++, null));
                }
                options.add(new IngestVariantOption(en.getKey(), options.size(), vals));
            }
        }
        // Variantes/SKU desde el JSON; si no vienen, una variante por defecto.
        java.util.List<IngestVariant> variants = new java.util.ArrayList<>();
        if (r.getVariants() != null && !r.getVariants().isEmpty()) {
            int vi = 0;
            for (var v : r.getVariants()) {
                String sku = (v.getSku() != null && !v.getSku().isBlank()) ? v.getSku()
                        : externalId + "-" + (vi + 1);
                variants.add(new IngestVariant(sku, sku, esTitle,
                        v.getPrice() != null ? v.getPrice() : price, v.getStock() != null ? v.getStock() : 0,
                        v.getImageUrl(), v.getOptionValues() != null ? v.getOptionValues() : java.util.Map.of()));
                vi++;
            }
        } else {
            // DROP-680: sin variantes declaradas no se inventa inventario (stock real desconocido = 0).
            variants.add(new IngestVariant(externalId + "-DEF", externalId + "-DEF", esTitle, price, 0, null,
                    java.util.Map.of()));
        }
        // Tiered pricing (DROP-669): se persisten SOLO los tramos reales que declara el proveedor.
        // Si no hay tramos no se inventa ninguno: la ficha muestra únicamente el precio unitario.
        java.util.List<IngestPriceTier> tiers = new java.util.ArrayList<>();
        if (r.getTieredPricing() != null && !r.getTieredPricing().isEmpty()) {
            for (var t : r.getTieredPricing()) {
                tiers.add(new IngestPriceTier(t.getMinQty() != null ? t.getMinQty() : 1, t.getMaxQty(),
                        t.getUnitPrice() != null ? t.getUnitPrice() : price,
                        t.getCurrency() != null ? t.getCurrency() : "CNY"));
            }
        }
        // DROP-680: rating, recompra y reseñas NO se inventan. Si el proveedor no los declara quedan
        // nulos/0; el desglose real (ratingBreakdown) se persiste y deriva reviewCount/rating en applyLogistics.
        var req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc, r.getManufacturer(),
                r.getMoq() != null ? r.getMoq() : 1, price, "CNY", r.getWeightGrams(),
                r.getMonthlySales() != null ? r.getMonthlySales() : 0, null,
                r.getRating(), 0,
                "https://detail.1688.com/offer/" + externalId + ".html", supplierId, cat.getId(), images,
                options, variants, tiers);
        String ptTitle = (r.getTitlePt() != null && !r.getTitlePt().isBlank()) ? r.getTitlePt() : esTitle;
        // El writer corre @Transactional: aplica títulos+descripciones por idioma y la logística
        // (vía el hook) sobre la entidad gestionada, evitando LazyInitialization.
        UUID id = catalogFillWriter.write(req, esTitle, enTitle, ptTitle, zhTitle, esDesc, r.getDescriptionEn(),
                r.getDescriptionPt(), r.getDescriptionZh(), p -> applyLogistics(p, r));
        // El writer publica como ACTIVE por defecto; si el operador pidió DRAFT, se respeta.
        if (r.getStatus() != null && "DRAFT".equalsIgnoreCase(r.getStatus().trim())) {
            productJpaRepository.findById(id).ifPresent(pp -> {
                pp.setStatus(ProductStatus.DRAFT);
                productJpaRepository.save(pp);
                productIndexer.indexProduct(id);
            });
        }
        createBulkReviews(id, r.getReviews());
        return id;
    }

    /** Crea las reseñas reales del producto desde la carga masiva (cada una con su idioma). */
    private void createBulkReviews(UUID productId,
            java.util.List<com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        ProductEntity ref = productJpaRepository.findById(productId).orElse(null);
        if (ref == null) {
            return;
        }
        for (var rv : reviews) {
            if ((rv.getBody() == null || rv.getBody().isBlank()) && (rv.getTitle() == null || rv.getTitle().isBlank())) {
                continue;
            }
            short rating = rv.getRating() != null ? (short) Math.max(1, Math.min(5, rv.getRating())) : 5;
            var e = com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity.builder()
                    .product(ref)
                    .authorName(rv.getAuthorName() != null && !rv.getAuthorName().isBlank() ? rv.getAuthorName().trim()
                            : "Anónimo")
                    .authorCountry(rv.getAuthorCountry())
                    .rating(rating)
                    .title(rv.getTitle())
                    .body(rv.getBody())
                    .tags(rv.getTags() != null ? String.join(",", rv.getTags()) : null)
                    .verifiedPurchase(Boolean.TRUE.equals(rv.getVerifiedPurchase()))
                    .approved(true)
                    .language(rv.getLanguage() != null && !rv.getLanguage().isBlank()
                            ? rv.getLanguage().trim().toLowerCase()
                            : "es")
                    .build();
            productReviewJpaRepositoryAdapter.save(e);
        }
    }

    /** Fija los campos de logística/aduana sobre la entidad gestionada (dentro de la transacción del writer). */
    private void applyLogistics(ProductEntity p, com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn r) {
        if (r.getPackageWeightGrams() != null) {
            p.setPackageWeightGrams(r.getPackageWeightGrams());
        }
        if (r.getLengthMm() != null) {
            p.setLengthMm(r.getLengthMm());
        }
        if (r.getWidthMm() != null) {
            p.setWidthMm(r.getWidthMm());
        }
        if (r.getHeightMm() != null) {
            p.setHeightMm(r.getHeightMm());
        }
        if (r.getCountryOfOrigin() != null && !r.getCountryOfOrigin().isBlank()) {
            p.setCountryOfOrigin(r.getCountryOfOrigin());
        }
        if (r.getHsCode() != null && !r.getHsCode().isBlank()) {
            p.setHsCode(r.getHsCode());
        }
        if (r.getCertifications() != null && !r.getCertifications().isEmpty()) {
            p.setCertifications(r.getCertifications());
        }
        if (r.getShipFrom() != null && !r.getShipFrom().isBlank()) {
            p.setShipFrom(r.getShipFrom());
        }
        if (r.getLeadTimeDays() != null) {
            p.setLeadTimeDays(r.getLeadTimeDays());
        }
        if (r.getVideoUrl() != null && !r.getVideoUrl().isBlank()) {
            p.setVideoUrl(r.getVideoUrl());
            p.setHasVideo(true);
        }
        // v44: campos internacionales adicionales.
        if (r.getVideoUrls() != null && !r.getVideoUrls().isEmpty()) {
            p.setVideoUrls(r.getVideoUrls());
            p.setHasVideo(true);
        }
        if (r.getSalesRegions() != null && !r.getSalesRegions().isEmpty()) {
            p.setSalesRegions(r.getSalesRegions());
        }
        if (r.getRatingBreakdown() != null && !r.getRatingBreakdown().isEmpty()) {
            // DROP-676/680: desglose por estrellas REAL. De él derivamos reviewCount (suma) y, si el
            // proveedor no declaró una media explícita, el rating ponderado. Nada inventado.
            p.setRatingBreakdown(r.getRatingBreakdown());
            int total = 0;
            long weighted = 0;
            for (var e : r.getRatingBreakdown().entrySet()) {
                int stars;
                try {
                    stars = Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                int cnt = e.getValue() != null ? e.getValue() : 0;
                total += cnt;
                weighted += (long) stars * cnt;
            }
            p.setReviewCount(total);
            if (r.getRating() == null && total > 0) {
                p.setRating(java.math.BigDecimal.valueOf((double) weighted / total)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
            }
        }
        if (r.getCrossBorderSupport() != null && !r.getCrossBorderSupport().isEmpty()) {
            p.setCrossBorderSupport(r.getCrossBorderSupport());
        }
        if (r.getDropshipShipped30d() != null) {
            p.setDropshipShipped30d(r.getDropshipShipped30d());
        }
        if (r.getDropshipPickupRate48h() != null) {
            p.setDropshipPickupRate48h(r.getDropshipPickupRate48h());
        }
        // supplierSkuId + peso/dimensiones por variante (DROP-675): se matchean por SKU sobre las
        // variantes ya creadas por upsertProduct.
        if (r.getVariants() != null && !r.getVariants().isEmpty()) {
            java.util.Map<String, com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant> bySku =
                    new java.util.HashMap<>();
            for (var v : r.getVariants()) {
                if (v.getSku() != null && !v.getSku().isBlank()) {
                    bySku.put(v.getSku(), v);
                }
            }
            if (!bySku.isEmpty()) {
                for (ProductVariantEntity pv : p.getVariants()) {
                    var v = bySku.get(pv.getSku());
                    if (v == null) {
                        continue;
                    }
                    if (v.getSupplierSkuId() != null && !v.getSupplierSkuId().isBlank()) {
                        pv.setSupplierSkuId(v.getSupplierSkuId());
                    }
                    if (v.getWeightGrams() != null) {
                        pv.setWeightGrams(v.getWeightGrams());
                    }
                    if (v.getPackageWeightGrams() != null) {
                        pv.setPackageWeightGrams(v.getPackageWeightGrams());
                    }
                    if (v.getLengthMm() != null) {
                        pv.setLengthMm(v.getLengthMm());
                    }
                    if (v.getWidthMm() != null) {
                        pv.setWidthMm(v.getWidthMm());
                    }
                    if (v.getHeightMm() != null) {
                        pv.setHeightMm(v.getHeightMm());
                    }
                }
            }
        }
        // Traducciones por idioma de los valores de variación (Color/Talla) desde el JSON: se matchean
        // por value_zh sobre los valores ya creados. Reemplaza las traducciones de ese valor.
        if (r.getVariantAxes() != null) {
            java.util.Map<String, java.util.Map<String, String>> byValue = new java.util.HashMap<>();
            for (var ax : r.getVariantAxes()) {
                if (ax.getValueTranslations() != null) {
                    byValue.putAll(ax.getValueTranslations());
                }
            }
            if (!byValue.isEmpty()) {
                for (var opt : p.getVariantOptions()) {
                    for (var vv : opt.getValues()) {
                        var trMap = byValue.get(vv.getValueZh());
                        if (trMap == null || trMap.isEmpty()) {
                            continue;
                        }
                        vv.getTranslations().clear();
                        for (var e : trMap.entrySet()) {
                            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null
                                    && !e.getValue().isBlank()) {
                                vv.getTranslations().add(com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity
                                        .builder().variantValue(vv).language(e.getKey().trim().toLowerCase())
                                        .value(e.getValue().trim()).build());
                            }
                        }
                    }
                }
            }
        }
        // Atributos taxonómicos (facetas): se reemplazan en cada import.
        if (r.getAttributes() != null && !r.getAttributes().isEmpty()) {
            productAttributeRepository.deleteAll(productAttributeRepository.findByProduct_Id(p.getId()));
            for (var a : r.getAttributes()) {
                if (a.getKey() != null && !a.getKey().isBlank() && a.getValue() != null && !a.getValue().isBlank()) {
                    // DROP-672: locale opcional (es/en/pt/zh); en blanco = neutral (faceta).
                    String loc = a.getLocale() != null && !a.getLocale().isBlank() ? a.getLocale().trim().toLowerCase()
                            : null;
                    productAttributeRepository.save(ProductAttributeEntity.builder().product(p).attrKey(a.getKey())
                            .attrValue(a.getValue()).locale(loc).createdAt(Instant.now()).build());
                }
            }
        }
        // Ficha técnica por idioma: se reemplaza en cada import.
        if (r.getSpecifications() != null && !r.getSpecifications().isEmpty()) {
            productSpecificationRepository
                    .deleteAll(productSpecificationRepository.findByProduct_IdOrderByPositionAsc(p.getId()));
            int sp = 0;
            for (var s : r.getSpecifications()) {
                if (s.getKey() != null && !s.getKey().isBlank() && s.getValue() != null && !s.getValue().isBlank()) {
                    productSpecificationRepository.save(ProductSpecificationEntity.builder().product(p)
                            .locale(s.getLocale() != null && !s.getLocale().isBlank() ? s.getLocale() : "es")
                            .specKey(s.getKey()).specValue(s.getValue())
                            .position(s.getPosition() != null ? s.getPosition() : sp).createdAt(Instant.now()).build());
                }
                sp++;
            }
        }
        // Las traducciones (título + descripción por idioma) las fija el writer dentro de su transacción.
        // DROP-679: como el writer publica el producto (status ACTIVE), generamos aquí el SEO por idioma
        // a partir de esas traducciones reales (las colecciones ya están adjuntas a la entidad gestionada).
        generateSeoMetadata(p);
    }

    @Override
    public com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut bulkCreateCategories(
            java.util.List<com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn> rows) {
        int created = 0, failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            try {
                if (r.getNameEs() == null || r.getNameEs().isBlank()) {
                    throw new BusinessException("nameEs es obligatorio");
                }
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

    /**
     * DROP-677: resuelve la categoría interna del producto al importar. Prioridad: (1) categorySlug
     * interno explícito; (2) mapeo por id de 1688; (3) mapeo por nombre de 1688. Si nada resuelve, se
     * rechaza la fila (no se inventa una categoría).
     */
    private CategoryEntity resolveBulkCategory(com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn r) {
        if (r.getCategorySlug() != null && !r.getCategorySlug().isBlank()) {
            String slug = r.getCategorySlug().trim();
            return categoryRepository.findBySlug(slug)
                    .orElseThrow(() -> new BusinessException("Categoría no encontrada: " + slug));
        }
        if (r.getCategory1688Id() != null && !r.getCategory1688Id().isBlank()) {
            var m = category1688MappingRepository.findByExternal1688Id(r.getCategory1688Id().trim());
            if (m.isPresent()) {
                return m.get().getCategory();
            }
        }
        if (r.getCategory1688Name() != null && !r.getCategory1688Name().isBlank()) {
            var m = category1688MappingRepository.findFirstByExternal1688NameIgnoreCase(r.getCategory1688Name().trim());
            if (m.isPresent()) {
                return m.get().getCategory();
            }
        }
        throw new BusinessException(
                "No se pudo resolver la categoría: indica categorySlug o un category1688Id/Name con mapeo definido");
    }

    @Override
    @Transactional
    public UUID upsertCategory1688Mapping(String external1688Id, String external1688Name, UUID categoryId) {
        if (external1688Id == null || external1688Id.isBlank()) {
            throw new BusinessException("external1688Id es obligatorio");
        }
        CategoryEntity cat = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId));
        var existing = category1688MappingRepository.findByExternal1688Id(external1688Id.trim());
        var m = existing.orElseGet(
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.Category1688MappingEntity::new);
        m.setExternal1688Id(external1688Id.trim());
        m.setExternal1688Name(external1688Name != null && !external1688Name.isBlank() ? external1688Name.trim() : null);
        m.setCategory(cat);
        return category1688MappingRepository.save(m).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut> listCategory1688Mappings() {
        return category1688MappingRepository.findAll().stream()
                .map(m -> new com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut(m.getId(),
                        m.getExternal1688Id(), m.getExternal1688Name(),
                        m.getCategory() != null ? m.getCategory().getId() : null,
                        m.getCategory() != null ? m.getCategory().getSlug() : null,
                        categoryDisplayName(m.getCategory())))
                .toList();
    }

    @Override
    @Transactional
    public void deleteCategory1688Mapping(UUID id) {
        category1688MappingRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut> listCategoryAttributeSchema(
            UUID categoryId) {
        return categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(categoryId).stream()
                .map(s -> new com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut(s.getId(),
                        s.getCategory() != null ? s.getCategory().getId() : null, s.getAttrKey(), s.getLabel(),
                        s.isRequired(), s.getPosition()))
                .toList();
    }

    @Override
    @Transactional
    public UUID upsertCategoryAttributeSchema(UUID categoryId, String attrKey, String label, boolean required,
            int position) {
        if (attrKey == null || attrKey.isBlank()) {
            throw new BusinessException("attrKey es obligatorio");
        }
        CategoryEntity cat = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException("Category not found: " + categoryId));
        var existing = categoryAttributeSchemaRepository.findByCategory_IdAndAttrKey(categoryId, attrKey.trim());
        var s = existing.orElseGet(
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity::new);
        s.setCategory(cat);
        s.setAttrKey(attrKey.trim());
        s.setLabel(label != null && !label.isBlank() ? label.trim() : null);
        s.setRequired(required);
        s.setPosition(position);
        return categoryAttributeSchemaRepository.save(s).getId();
    }

    @Override
    @Transactional
    public void deleteCategoryAttributeSchema(UUID id) {
        categoryAttributeSchemaRepository.deleteById(id);
    }

    /** Nombre legible de una categoría: traducción ES, si no nameZh, si no el slug. */
    private String categoryDisplayName(CategoryEntity c) {
        if (c == null) {
            return null;
        }
        return c.getTranslations().stream().filter(tr -> "es".equalsIgnoreCase(tr.getLanguage())).findFirst()
                .map(com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(c.getNameZh() != null ? c.getNameZh() : c.getSlug());
    }

    private UUID resolveBulkSupplier(java.util.List<SupplierEntity> suppliers, String supplierExternalId,
            String supplierName) {
        if (supplierExternalId != null && !supplierExternalId.isBlank()) {
            return supplierRepository.findBySourceAndExternalId("1688", supplierExternalId)
                    .map(SupplierEntity::getId)
                    .orElseThrow(() -> new BusinessException("Proveedor no encontrado: " + supplierExternalId));
        }
        // Si se da el nombre del proveedor/fábrica, buscar o CREAR uno con ese nombre — no reutilizar
        // el primer proveedor por defecto (causaba que una camiseta apuntara a la fábrica de zapatos).
        if (supplierName != null && !supplierName.isBlank()) {
            String name = supplierName.trim();
            return supplierRepository.findFirstByNameIgnoreCase(name).map(SupplierEntity::getId).orElseGet(() -> {
                String ext = "NAME-" + SLUG.slugify(name);
                if (ext.length() > 100) {
                    ext = ext.substring(0, 100);
                }
                return supplierRepository.save(SupplierEntity.builder().source("1688").externalId(ext).name(name)
                        .verified(false).trustPass(false).build()).getId();
            });
        }
        if (suppliers.isEmpty())
            throw new BusinessException("No hay proveedores; crea uno antes de importar productos");
        return suppliers.get(0).getId();
    }

}
