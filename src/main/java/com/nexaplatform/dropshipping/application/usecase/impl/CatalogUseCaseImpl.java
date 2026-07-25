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
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
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
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.Category1688MappingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.Category1688MappingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryAttributeSchemaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.seed.CatalogFillWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.stream.Collectors;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORIES_FLAT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORY_TREE;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;
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
    private final ObjectStorageService objectStorage;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository productJpaRepository;
    private final ProductMapper productMapper;
    private final CatalogStorefrontMapper catalogStorefrontMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ProductVariantRepository variantRepository;
    private final ProductIndexer productIndexer;
    private final CategoryIndexer categoryIndexer;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductSpecificationRepository productSpecificationRepository;
    private final VariantValueRepository variantValueRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ProductBulkExportMapper bulkExportMapper;
    private final ImageMirrorService imageMirrorService;

    @PersistenceContext
    private EntityManager em;

    // @Lazy field injection breaks the CatalogUseCaseImpl <-> CatalogFillWriter constructor cycle
    // (the writer ingests through this same use case).
    @Autowired
    @Lazy
    private CatalogFillWriter catalogFillWriter;

    // DROP-677: mapeo de categorías 1688 → interna (inyección por campo para no alterar el constructor).
    @Autowired
    private Category1688MappingRepository category1688MappingRepository;

    // DROP-670: esquema de atributos por categoría (inyección por campo para no alterar el constructor).
    @Autowired
    private CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;

    // Reseñas reales en la carga masiva (inyección por campo para no alterar el constructor).
    @Autowired
    private ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

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
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
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
            // Imágenes: se conservan con su URL de origen; sin espejo a S3.
        }

        log.info("Upserted product {} ({} - {})", product.getId(), product.getSource(), product.getExternalId());
        return product;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProductsForAdmin(String status, UUID categoryId, String query, int page,
            int size, String language, String sort, Boolean verified) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200), adminSort(sort));
        ProductStatus st = parseStatusTolerant(status);
        // Free-text search runs server-side across the WHOLE catalogue and ALL languages (same rules as the
        // storefront), so the admin box finds products in any page and in any language — not just a client-side
        // substring over the current page.
        // needle "" (no nulo) evita el error de tipo de Postgres al bindear null en el LIKE; la query usa
        // (:needle = '' OR ...). searchAdmin también sirve como ruta del filtro `verified` (con o sin texto/categoría).
        String needle = (query == null || query.isBlank()) ? "" : query.trim().toLowerCase();
        if (!needle.isEmpty() || verified != null) {
            return productJpaRepository.searchAdmin(st, categoryId, needle, verified, pageable)
                    .map(p -> productMapper.toSummary(p, language));
        }
        if (categoryId == null) {
            return listProducts(st, pageable, language);
        }
        Page<ProductEntity> entities = (st == null)
                ? productJpaRepository.findByCategoryId(categoryId, pageable)
                : productJpaRepository.findByCategoryIdAndStatus(categoryId, st, pageable);
        return entities.map(p -> productMapper.toSummary(p, language));
    }

    /**
     * Orden para el listado de admin. {@code price_*} ordena por el precio CNY persistido
     * ({@code basePrice}) — el margen es multiplicativo, así que el orden coincide con el de venta.
     */
    private static Sort adminSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.unsorted();
        }
        return switch (sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "basePrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "newest" -> Sort.by(Sort.Direction.DESC, "ingestedAt");
            case "oldest" -> Sort.by(Sort.Direction.ASC, "ingestedAt");
            default -> Sort.unsorted();
        };
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
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "#slug + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingChannelHolder).get() + ':' + T(com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils).isAdmin()")
    public ProductDetailView getProductBySlug(String slug, String language) {
        ProductEntity p = productJpaRepository.findWithDetailsBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Product not found: " + slug));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "'id:' + #id + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingChannelHolder).get() + ':' + T(com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils).isAdmin()")
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
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
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
            boolean zh = "zh".equalsIgnoreCase(tr.getLanguage());
            String title = tr.getTitle() != null && !tr.getTitle().isBlank() ? sanitizeSeo(tr.getTitle(), zh) : null;
            if (title == null || title.isBlank()) {
                continue;
            }
            if (tr.getMetaTitle() == null || tr.getMetaTitle().isBlank() || (!zh && hasCjk(tr.getMetaTitle()))) {
                String mt = title;
                if (p.getBrand() != null && !p.getBrand().isBlank()
                        && !title.toLowerCase().contains(p.getBrand().toLowerCase())
                        && (mt.length() + p.getBrand().length() + 3) <= 65) {
                    mt = mt + " | " + p.getBrand().trim();
                }
                tr.setMetaTitle(mt.length() > 200 ? mt.substring(0, 200) : mt);
            }
            if (tr.getMetaDescription() == null || tr.getMetaDescription().isBlank()
                    || (!zh && hasCjk(tr.getMetaDescription()))) {
                String base = tr.getShortDescription() != null && !tr.getShortDescription().isBlank()
                        ? tr.getShortDescription()
                        : (tr.getDescription() != null ? tr.getDescription() : title);
                String md = sanitizeSeo(base, zh);
                if (md.length() > 155) {
                    md = md.substring(0, 152).trim() + "…";
                }
                tr.setMetaDescription(md);
            }
        }
    }

    /**
     * DROP-686: para idiomas no-chinos, elimina caracteres CJK (p.ej. 露趾) que se cuelan del origen,
     * para que el SEO de un producto en español/inglés no contenga texto en chino. Colapsa espacios y
     * limpia separadores huérfanos que queden tras la eliminación.
     */
    /** ¿Contiene caracteres CJK (chino/japonés/coreano)? */
    private boolean hasCjk(String text) {
        return text != null && text.matches(".*[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF].*");
    }

    private String sanitizeSeo(String text, boolean zh) {
        if (text == null) {
            return "";
        }
        String t = text;
        if (!zh) {
            t = t.replaceAll("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]", " ");
            t = t.replaceAll("\\s*([|·,;])\\s*([|·,;])", " $1 "); // separadores duplicados
            t = t.replaceAll("\\s*([|·])\\s*$", "");             // separador colgante final
            t = t.replaceAll("^\\s*([|·,;])\\s*", "");           // separador colgante inicial
        }
        return t.replaceAll("\\s+", " ").trim();
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deletePriceTier(UUID productId, int minQty) {
        if (!productJpaRepository.existsById(productId))
            throw new NotFoundException("Product not found: " + productId);
        long removed = priceTierRepository.deleteByProductIdAndMinQty(productId, minQty);
        if (removed == 0)
            throw new NotFoundException("Price tier not found: product " + productId + ", minQty " + minQty);
        productIndexer.indexProduct(productId);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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
        // Verificación manual del admin (checkbox del listado): true = revisado OK, false = pendiente/reimportar.
        if (req.getVerified() != null)
            p.setVerified(req.getVerified());
        // Reasignar categoría desde el admin (selector de la ficha). Se resuelve por id y se valida que exista.
        if (req.getCategoryId() != null) {
            CategoryEntity cat = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + req.getCategoryId()));
            p.setCategory(cat);
        }
        // DROP-673: edición del vídeo real del producto desde el admin (cadena vacía lo elimina).
        if (req.getVideoUrl() != null) {
            String vu = req.getVideoUrl().trim();
            p.setVideoUrl(vu.isEmpty() ? null : vu);
            p.setHasVideo(!vu.isEmpty() || (p.getVideoUrls() != null && !p.getVideoUrls().isEmpty()));
        }
        boolean touchesTranslation = (req.getTitle() != null && !req.getTitle().isBlank())
                || req.getShortDescription() != null || req.getDescription() != null
                || req.getMetaTitle() != null || req.getMetaDescription() != null;
        if (touchesTranslation) {
            // Update the active-language translation (title/short/long description), not the canonical title_zh.
            var trOpt = p.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst();
            var tr = trOpt.orElseGet(() -> {
                var n = ProductTranslationEntity
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
            // DROP-688: edición manual del SEO (meta título/descripción) del idioma activo.
            if (req.getMetaTitle() != null)
                tr.setMetaTitle(req.getMetaTitle().isBlank() ? null : req.getMetaTitle().trim());
            if (req.getMetaDescription() != null)
                tr.setMetaDescription(req.getMetaDescription().isBlank() ? null : req.getMetaDescription().trim());
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
        // DROP-681: external_id es varchar(120). Con externalId largos (BULK-…), "-COPY-…" lo
        // desbordaba y el insert fallaba (500). Se capa la base para que el resultado quepa en 120.
        String copySuffix = "-COPY-" + (System.currentTimeMillis() % 100000);
        String base = src.getExternalId() != null ? src.getExternalId() : "PRODUCT";
        if (base.length() > 120 - copySuffix.length()) {
            base = base.substring(0, 120 - copySuffix.length());
        }
        String newExt = base + copySuffix;
        ProductEntity copy = ProductEntity.builder().source(src.getSource()).externalId(newExt)
                .titleZh(src.getTitleZh() + " (copy)").shortDescriptionZh(src.getShortDescriptionZh())
                .descriptionZh(src.getDescriptionZh()).brand(src.getBrand()).moq(src.getMoq())
                .basePrice(src.getBasePrice()).currency(src.getCurrency()).supplier(src.getSupplier())
                .category(src.getCategory()).status(ProductStatus.DRAFT).slug(buildSlug(src.getTitleZh(), newExt))
                .build();
        ProductEntity saved = productJpaRepository.save(copy);
        for (var tr : src.getTranslations()) {
            saved.getTranslations()
                    .add(ProductTranslationEntity
                            .builder().product(saved).language(tr.getLanguage())
                            .title((tr.getTitle() != null ? tr.getTitle() : "") + " (copy)")
                            .shortDescription(tr.getShortDescription()).description(tr.getDescription())
                            .provider("admin-duplicate").build());
        }
        productJpaRepository.save(saved);
        return productMapper.toDetail(saved, lang, Collections.emptyList());
    }

    @Override
    public BigDecimal computeTrendScore(ProductEntity p) {
        double salesNorm = Math.min(1.0, (p.getMonthlySales()) / 1000.0);
        double rating = p.getRating() != null ? p.getRating().doubleValue() / 5.0 : 0.0;
        double repurchase = p.getRepurchaseRate() != null ? p.getRepurchaseRate().doubleValue() / 100.0 : 0.0;
        double reviews = Math.min(1.0, p.getReviewCount() / 500.0);
        double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
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
        // Slugify descarta lo que no sea ASCII: con un título íntegramente en chino (o en cualquier otra
        // escritura no latina) devuelve "" y el slug quedaba en "-<externalId>". Se usa un prefijo neutro
        // para que nunca empiece por guion; quien tenga el título en escritura latina (el importador
        // masivo, ver CatalogFillWriter) lo reescribe después con algo legible.
        if (slug.isBlank())
            slug = "product";
        if (slug.length() > 100)
            slug = slug.substring(0, 100);
        String full = slug + "-" + externalId.toLowerCase();
        // slug es varchar(220): se capa por seguridad ante títulos + externalId largos.
        return full.length() > 220 ? full.substring(0, 220) : full;
    }

    /* ============ Reindex (admin) ============ */

    @Override
    public int reindexAllProducts() {
        int n = productIndexer.reindexAll();
        // Reindexar también arrastra el espejado: drena las imágenes PENDING (cada lote reindexa) para que
        // "reindexar" deje todo el catálogo visible en el escaparate, no solo actualice el índice.
        imageMirrorService.mirrorAllPendingAsync();
        return n;
    }

    @Override
    @Transactional
    public int backfillVariantAxes() {
        int filled = 0;
        for (ProductEntity p : productJpaRepository.findAll()) {
            if (!p.getVariantOptions().isEmpty() || p.getVariants() == null || p.getVariants().isEmpty()) {
                continue;
            }
            LinkedHashMap<String, LinkedHashSet<String>> derived = new LinkedHashMap<>();
            for (var v : p.getVariants()) {
                if (v.getOptions() == null) {
                    continue;
                }
                for (var e : v.getOptions().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                        continue;
                    }
                    derived.computeIfAbsent(e.getKey().trim(), k -> new LinkedHashSet<>())
                            .add(e.getValue().trim());
                }
            }
            if (derived.isEmpty()) {
                continue;
            }
            int op = 0;
            for (var en : derived.entrySet()) {
                var opt = new VariantOptionEntity();
                opt.setProduct(p);
                opt.setNameZh(en.getKey());
                opt.setName(en.getKey());
                opt.setPosition(op++);
                int vp = 0;
                for (String val : en.getValue()) {
                    var vv = new VariantValueEntity();
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
            boolean missing = p.getTranslations().stream().anyMatch(tr -> {
                boolean zh = "zh".equalsIgnoreCase(tr.getLanguage());
                return tr.getTitle() != null && !tr.getTitle().isBlank()
                        && (tr.getMetaTitle() == null || tr.getMetaTitle().isBlank()
                                || tr.getMetaDescription() == null || tr.getMetaDescription().isBlank()
                                // DROP-686: meta contaminado con CJK en idioma no-chino → regenerar.
                                || (!zh && (hasCjk(tr.getMetaTitle()) || hasCjk(tr.getMetaDescription()))));
            });
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
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
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
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
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
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public VariantView updateVariantPrice(UUID variantId, BigDecimal price) {
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
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deleteVariantValue(UUID valueId) {
        var v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException("Variant value"));
        var opt = v.getOption();
        UUID productId = (opt != null && opt.getProduct() != null) ? opt.getProduct().getId() : null;
        if (productId != null) {
            // Nombre del eje tal como se guarda en product_variant.options_json ("Color"/"Talla"/…).
            String optName = (opt.getName() != null && !opt.getName().isBlank()) ? opt.getName() : opt.getNameZh();
            // Borra las combinaciones (product_variant) que usan este valor en ese eje (por etiqueta o canónico).
            jdbcTemplate.update(
                    "DELETE FROM product_variant WHERE product_id = ? AND (options_json->>? = ? OR options_json->>? = ?)",
                    productId, optName, v.getValueZh(), optName, v.getValue());
        }
        variantValueRepository.delete(v);
        if (productId != null) {
            productIndexer.indexProduct(productId);
        }
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void setVariantValueTranslation(UUID valueId, String language, String value) {
        if (language == null || language.isBlank()) {
            throw new BusinessException("language es obligatorio");
        }
        var v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException("Variant value"));
        String lang = language.trim().toLowerCase();
        String val = value != null ? value.trim() : null;
        v.getTranslations().removeIf(tt -> lang.equalsIgnoreCase(tt.getLanguage()));
        if (val != null && !val.isEmpty()) {
            v.getTranslations().add(VariantValueTranslationEntity
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
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void deleteVariant(UUID variantId) {
        ProductVariantEntity v = variantRepository.findById(variantId)
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        UUID productId = v.getProduct().getId();
        variantRepository.delete(v);
        productIndexer.indexProduct(productId);
    }

    /* ============ Images (product gallery) ============ */

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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
        // Si la URL ya apunta a NUESTRO storage (S3/MinIO) está lista → MIRRORED. Si es externa
        // (1688/alicdn u otro CDN), la dejamos PENDING para que el ImageMirrorService la descargue y la
        // suba a S3 (y deje de depender del hotlink). Persist vía el repo para devolver el id generado.
        boolean alreadyOurs = url != null && objectStorage.publicUrl() != null
                && !objectStorage.publicUrl().isBlank() && url.startsWith(objectStorage.publicUrl());
        ProductImageEntity img = ProductImageEntity.builder().product(product).position(nextPos)
                .role(asMain ? "MAIN" : "GALLERY").sourceUrl(url).cdnUrl(alreadyOurs ? url : null)
                .mirrorStatus(alreadyOurs ? MirrorStatus.MIRRORED : MirrorStatus.PENDING).build();
        ProductImageEntity saved = imageRepository.save(img);
        productIndexer.indexProduct(productId);
        // Edición: si la imagen añadida es externa (PENDING), espejarla YA para que se vea al instante.
        if (!alreadyOurs) {
            mirrorNewImagesAfterCommit(List.of(productId));
        }
        return productMapper.toImageView(saved);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deleteProductVideo(UUID id) {
        ProductEntity product = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        product.setVideoUrl(null);
        product.setHasVideo(false);
        product.setVideoUrls(null);
        productJpaRepository.save(product);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deleteProductImage(UUID imageId) {
        ProductImageEntity img = imageRepository.findById(imageId)
                .orElseThrow(() -> new NotFoundException("Image not found"));
        ProductEntity product = img.getProduct();
        UUID productId = product != null ? product.getId() : null;
        // Product.images es @OneToMany(orphanRemoval=true): NO basta con imageRepository.delete(img),
        // porque el producto gestionado sigue referenciando la imagen en su colección y Hibernate la
        // re-asocia en el flush (el borrado "se pierde" → respondía 204 pero quedaban las 4). Hay que
        // QUITARLA de la colección del padre; orphanRemoval emite entonces el DELETE. Forzamos el flush
        // para que el reindex y la respuesta reflejen ya el borrado persistido.
        if (product != null && product.getImages() != null) {
            product.getImages().removeIf(i -> imageId.equals(i.getId()));
        } else {
            imageRepository.delete(img);
        }
        imageRepository.flush();
        if (productId != null) {
            productIndexer.indexProduct(productId);
        }
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void reorderProductImages(UUID productId, List<UUID> imageIds) {
        ProductEntity product = productJpaRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        if (imageIds == null || imageIds.isEmpty()) {
            return;
        }
        // Reasigna position según el orden recibido; la primera pasa a MAIN y el resto a GALLERY.
        // Las imágenes no incluidas en la lista se colocan al final preservando su orden previo.
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < imageIds.size(); i++) {
            order.put(imageIds.get(i), i);
        }
        int tail = imageIds.size();
        for (ProductImageEntity img : product.getImages()) {
            Integer pos = order.get(img.getId());
            if (pos == null) {
                img.setPosition(tail++);
            } else {
                img.setPosition(pos);
                img.setRole(pos == 0 ? "MAIN" : "GALLERY");
            }
        }
        imageRepository.flush();
        productIndexer.indexProduct(productId);
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
        v.setOptions(req.getOptions() != null ? req.getOptions() : Map.of());
        v.setActive(req.getActive() == null || req.getActive());
    }

    /** Main image URL of an ingest payload (role MAIN first, then lowest position). */
    private String mainImageUrlOf(List<IngestImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream().filter(i -> i.sourceUrl() != null && !i.sourceUrl().isBlank())
                .min(Comparator
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
                .min(Comparator
                        .comparingInt((ProductImageEntity i) -> "MAIN".equalsIgnoreCase(i.getRole()) ? 0 : 1)
                        .thenComparingInt(ProductImageEntity::getPosition))
                .map(i -> i.getCdnUrl() != null && !i.getCdnUrl().isBlank() ? i.getCdnUrl() : i.getSourceUrl())
                .orElse(null);
    }


    /* ============ Bulk import (admin) ============ */

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public BulkResultDtoOut bulkCreateProducts(
            List<BulkProductDtoIn> rows) {
        int created = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        List<SupplierEntity> suppliers = supplierRepository.findAll();
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            try {
                createdIds.add(buildAndWriteProduct(r, suppliers));
                created++;
            } catch (Exception e) {
                failed++;
                errors.add("Fila " + (i + 1) + ": " + ErrorMessages.humanize(e));
            }
        }
        // Reindexar SOLO lo recién creado (no los ~1000 existentes) y espejar sus imágenes YA en background:
        // el mirror pone hasImage=true y reindexa → el producto aparece en el escaparate casi al instante.
        for (UUID id : createdIds) {
            productIndexer.indexProduct(id);
        }
        mirrorNewImagesAfterCommit(createdIds);
        return new BulkResultDtoOut(created, failed, errors);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BulkProductDtoIn> exportProducts(int from, int to) {
        int safeFrom = Math.max(1, from);
        int safeTo = Math.max(safeFrom, to);
        int offset = safeFrom - 1;
        int limit = safeTo - safeFrom + 1;
        List<ProductEntity> products = em
                .createQuery("SELECT p FROM ProductEntity p ORDER BY p.id ASC", ProductEntity.class)
                .setFirstResult(offset).setMaxResults(limit).getResultList();
        List<BulkProductDtoIn> out = new ArrayList<>();
        for (ProductEntity p : products) {
            List<ProductAttributeEntity> attributes = em.createQuery(
                    "SELECT a FROM ProductAttributeEntity a WHERE a.product.id = :id", ProductAttributeEntity.class)
                    .setParameter("id", p.getId()).getResultList();
            List<ProductSpecificationEntity> specs = em.createQuery(
                    "SELECT s FROM ProductSpecificationEntity s WHERE s.product.id = :id ORDER BY s.position",
                    ProductSpecificationEntity.class).setParameter("id", p.getId()).getResultList();
            List<ProductPriceTierEntity> tiers = em.createQuery(
                    "SELECT t FROM ProductPriceTierEntity t WHERE t.product.id = :id ORDER BY t.minQty",
                    ProductPriceTierEntity.class).setParameter("id", p.getId()).getResultList();
            List<ProductReviewEntity> reviews = em.createQuery(
                    "SELECT r FROM ProductReviewEntity r WHERE r.product.id = :id ORDER BY r.createdAt",
                    ProductReviewEntity.class).setParameter("id", p.getId()).getResultList();
            out.add(bulkExportMapper.toBulk(p, attributes, specs, tiers, reviews));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductExportBatch exportBatchAfter(UUID afterId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        // Keyset pagination by id. A native query with an explicit uuid cast is used because Hibernate does
        // not reliably translate the JPQL "p.id > :afterId" comparison on a UUID column (it silently returns
        // no rows past a point), which truncated the stream. Native SQL uses Postgres' native uuid ordering.
        @SuppressWarnings("unchecked")
        List<ProductEntity> products = em.createNativeQuery(
                "SELECT * FROM product WHERE (CAST(:afterId AS uuid) IS NULL OR id > CAST(:afterId AS uuid)) "
                        + "ORDER BY id ASC LIMIT :lim", ProductEntity.class)
                .setParameter("afterId", afterId != null ? afterId.toString() : null)
                .setParameter("lim", safeLimit)
                .getResultList();
        if (products.isEmpty()) {
            return new ProductExportBatch(List.of(), null);
        }
        List<UUID> ids = products.stream().map(ProductEntity::getId).toList();
        Map<UUID, List<ProductAttributeEntity>> attributesByProduct = em.createQuery(
                "SELECT a FROM ProductAttributeEntity a WHERE a.product.id IN :ids", ProductAttributeEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(a -> a.getProduct().getId()));
        Map<UUID, List<ProductSpecificationEntity>> specsByProduct = em.createQuery(
                "SELECT s FROM ProductSpecificationEntity s WHERE s.product.id IN :ids ORDER BY s.position",
                ProductSpecificationEntity.class).setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(s -> s.getProduct().getId()));
        Map<UUID, List<ProductPriceTierEntity>> tiersByProduct = em.createQuery(
                "SELECT t FROM ProductPriceTierEntity t WHERE t.product.id IN :ids ORDER BY t.minQty",
                ProductPriceTierEntity.class).setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(t -> t.getProduct().getId()));
        Map<UUID, List<ProductReviewEntity>> reviewsByProduct = em.createQuery(
                "SELECT r FROM ProductReviewEntity r WHERE r.product.id IN :ids ORDER BY r.createdAt",
                ProductReviewEntity.class).setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(r -> r.getProduct().getId()));
        List<BulkProductDtoIn> out = new ArrayList<>(products.size());
        for (ProductEntity p : products) {
            out.add(bulkExportMapper.toBulk(p,
                    attributesByProduct.getOrDefault(p.getId(), List.of()),
                    specsByProduct.getOrDefault(p.getId(), List.of()),
                    tiersByProduct.getOrDefault(p.getId(), List.of()),
                    reviewsByProduct.getOrDefault(p.getId(), List.of())));
        }
        return new ProductExportBatch(out, products.get(products.size() - 1).getId());
    }

    @Override
    @Transactional(readOnly = true)
    public BulkProductDtoIn exportProduct(UUID id) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
        List<ProductAttributeEntity> attributes = em.createQuery(
                "SELECT a FROM ProductAttributeEntity a WHERE a.product.id = :id", ProductAttributeEntity.class)
                .setParameter("id", id).getResultList();
        List<ProductSpecificationEntity> specs = em.createQuery(
                "SELECT s FROM ProductSpecificationEntity s WHERE s.product.id = :id ORDER BY s.position",
                ProductSpecificationEntity.class).setParameter("id", id).getResultList();
        List<ProductPriceTierEntity> tiers = em.createQuery(
                "SELECT t FROM ProductPriceTierEntity t WHERE t.product.id = :id ORDER BY t.minQty",
                ProductPriceTierEntity.class).setParameter("id", id).getResultList();
        List<ProductReviewEntity> reviews = em.createQuery(
                "SELECT r FROM ProductReviewEntity r WHERE r.product.id = :id ORDER BY r.createdAt",
                ProductReviewEntity.class).setParameter("id", id).getResultList();
        return bulkExportMapper.toBulk(p, attributes, specs, tiers, reviews);
    }

    @Override
    @Transactional(readOnly = true)
    public long countProducts() {
        return em.createQuery("SELECT COUNT(p) FROM ProductEntity p", Long.class).getSingleResult();
    }

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public UUID createProductManual(BulkProductDtoIn req) {
        UUID id = buildAndWriteProduct(req, supplierRepository.findAll());
        productIndexer.indexProduct(id);
        mirrorNewImagesAfterCommit(List.of(id));
        return id;
    }

    /**
     * Espeja YA (en background, tras el commit) las imágenes PENDING de los productos indicados, y reindexa.
     * Se llama en TODA operación que introduce imágenes de producto —alta individual, alta masiva y edición
     * (añadir imagen)— para que el producto aparezca en el escaparate casi al instante. Si hay transacción
     * activa, se difiere a {@code afterCommit} (si no, el hilo async no vería las filas aún sin commitear);
     * si no la hay, se dispara directo.
     */
    private void mirrorNewImagesAfterCommit(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        List<UUID> ids = List.copyOf(productIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    imageMirrorService.mirrorProductsAsync(ids);
                }
            });
        } else {
            imageMirrorService.mirrorProductsAsync(ids);
        }
    }

    private static final List<String> PRODUCT_CHILD_TABLES = List.of("product_price_tier", "product_tag_link",
            "product_specification", "product_attribute", "product_keyword", "product_history", "product_review",
            "product_warehouse_stock", "product_sync_state", "bestseller_ranking", "category_ranking", "trend_signal",
            "shop_product_listing", "pod_design");

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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

    /** Rellena el campo fijo de un idioma (título/descr.) desde el mapa `translations` si está vacío. */
    private void mergeTranslationField(String lang,
            Map<String, BulkProductDtoIn.BulkTranslation> m,
            Supplier<String> getTitle, Consumer<String> setTitle,
            Supplier<String> getDesc, Consumer<String> setDesc) {
        var tr = m.get(lang);
        if (tr == null) {
            return;
        }
        if ((getTitle.get() == null || getTitle.get().isBlank()) && tr.getTitle() != null && !tr.getTitle().isBlank()) {
            setTitle.accept(tr.getTitle().trim());
        }
        if ((getDesc.get() == null || getDesc.get().isBlank()) && tr.getDescription() != null
                && !tr.getDescription().isBlank()) {
            setDesc.accept(tr.getDescription().trim());
        }
    }

    /** Builds the heavy ingest request from a friendly row, persists it (via the writer) and returns its id. */
    private UUID buildAndWriteProduct(BulkProductDtoIn r,
            List<SupplierEntity> suppliers) {
        CategoryEntity cat = resolveBulkCategory(r);
        // DROP-670: si la categoría define atributos obligatorios, el producto debe traerlos (integridad).
        var requiredSchema = categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(cat.getId()).stream()
                .filter(CategoryAttributeSchemaEntity::isRequired)
                .map(CategoryAttributeSchemaEntity::getAttrKey)
                .toList();
        if (!requiredSchema.isEmpty()) {
            Set<String> provided = new HashSet<>();
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
        // Idiomas ilimitados: si el contenido viene en el mapa `translations`, se rellenan los campos
        // canónicos es/en/pt/zh donde falten (para slug/validación/writer); el resto de idiomas del
        // mapa se upsertan luego en applyLogistics. Si no hay 'es' explícito, se usa el primer idioma
        // disponible como canónico para que el alta no falle por requerir titleEs.
        if (r.getTranslations() != null && !r.getTranslations().isEmpty()) {
            var m = r.getTranslations();
            mergeTranslationField("es", m, r::getTitleEs, r::setTitleEs, r::getDescriptionEs, r::setDescriptionEs);
            mergeTranslationField("en", m, r::getTitleEn, r::setTitleEn, r::getDescriptionEn, r::setDescriptionEn);
            mergeTranslationField("pt", m, r::getTitlePt, r::setTitlePt, r::getDescriptionPt, r::setDescriptionPt);
            mergeTranslationField("zh", m, r::getTitleZh, r::setTitleZh, r::getDescriptionZh, r::setDescriptionZh);
            if (r.getTitleEs() == null || r.getTitleEs().isBlank()) {
                var any = m.values().stream().filter(t -> t != null && t.getTitle() != null && !t.getTitle().isBlank())
                        .findFirst().orElse(null);
                if (any != null) {
                    r.setTitleEs(any.getTitle().trim());
                    if (r.getDescriptionEs() == null || r.getDescriptionEs().isBlank()) {
                        r.setDescriptionEs(any.getDescription());
                    }
                }
            }
        }
        String esTitle = r.getTitleEs();
        // DROP-682: el título es obligatorio en al menos un idioma; mensaje claro (no genérico).
        if (esTitle == null || esTitle.isBlank()) {
            throw new BusinessException("Falta el título del producto en al menos un idioma (titleEs o translations).");
        }
        String enTitle = (r.getTitleEn() != null && !r.getTitleEn().isBlank()) ? r.getTitleEn() : esTitle;
        String zhTitle = (r.getTitleZh() != null && !r.getTitleZh().isBlank()) ? r.getTitleZh() : esTitle;
        String esDesc = (r.getDescriptionEs() != null && !r.getDescriptionEs().isBlank()) ? r.getDescriptionEs()
                : esTitle;
        // DROP-680: el precio es dato real obligatorio; no se inventa. Si no viene explícito se toma
        // del tramo de precio más bajo (price break real); si tampoco hay tramos, se rechaza la fila.
        BigDecimal price = r.getPrice();
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
        // Envío e IVA (CNY) son OBLIGATORIOS en la carga (decisión del usuario). Sin ellos no se calcula
        // el total (base×margen + iva + envío), así que se rechaza la fila con un mensaje claro.
        if (r.getShippingCny() == null) {
            throw new BusinessException("Falta el envío (shippingCny) del producto: " + esTitle);
        }
        if (r.getIvaCny() == null) {
            throw new BusinessException("Falta el IVA (ivaCny) del producto: " + esTitle);
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
        // UPSERT idempotente por externalId: el producto se ACTUALIZA EN SITIO (mismo id, se preservan
        // enlaces/favoritos/pedidos). El producto y sus traducciones ya los upserta upsertProduct/writer;
        // aquí solo limpiamos las COLECCIONES HIJAS que se reconstruyen (variantes/opciones/imágenes/
        // atributos/specs/tiers) por product_id ANTES de recrearlas, para no duplicar al reimportar. NO se
        // borra el producto padre, así que su id no cambia (a diferencia de un delete+create).
        if (r.getExternalId() != null && !r.getExternalId().isBlank()) {
            productJpaRepository.findFirstByExternalId(externalId).ifPresent(existing -> {
                UUID exId = existing.getId();
                for (String table : PRODUCT_CHILD_TABLES) {
                    jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", exId);
                }
            });
        }
        // Imágenes del producto. Se aceptan varias claves (imageUrls/images/photos/... vía @JsonAlias)
        // y el atajo `imageUrl` (string suelto). Si no hay NINGUNA a nivel de producto, se usan como
        // respaldo las imágenes de las variantes (o de los valores/colores del eje) para no rechazar
        // un producto cuya única imagen vive en la variante. Se deduplica preservando el orden.
        LinkedHashSet<String> imageUrlSet = new LinkedHashSet<>();
        if (r.getImageUrls() != null) {
            for (String u : r.getImageUrls()) {
                if (u != null && !u.isBlank()) {
                    imageUrlSet.add(u.trim());
                }
            }
        }
        if (r.getImageUrl() != null && !r.getImageUrl().isBlank()) {
            imageUrlSet.add(r.getImageUrl().trim());
        }
        if (imageUrlSet.isEmpty()) {
            if (r.getVariants() != null) {
                for (var v : r.getVariants()) {
                    if (v.getImageUrl() != null && !v.getImageUrl().isBlank()) {
                        imageUrlSet.add(v.getImageUrl().trim());
                    }
                }
            }
            if (r.getVariantAxes() != null) {
                for (var ax : r.getVariantAxes()) {
                    if (ax.getValueImages() != null) {
                        for (String u : ax.getValueImages().values()) {
                            if (u != null && !u.isBlank()) {
                                imageUrlSet.add(u.trim());
                            }
                        }
                    }
                }
            }
        }
        List<IngestImage> images = new ArrayList<>();
        int imgPos = 0;
        for (String u : imageUrlSet) {
            images.add(new IngestImage(u, imgPos, imgPos == 0 ? "MAIN" : "GALLERY"));
            imgPos++;
        }
        // Calidad de datos: NO se importan productos sin imagen real (ni de producto ni de variante).
        if (images.isEmpty()) {
            throw new BusinessException("El producto '"
                    + (r.getExternalId() != null && !r.getExternalId().isBlank() ? r.getExternalId() : esTitle)
                    + "' no tiene imágenes — indica al menos una en 'imageUrls' (también vale 'images' o el "
                    + "atajo 'imageUrl', o una imagen de variante).");
        }
        // Ejes de variación (Color/Talla) desde el JSON.
        List<IngestVariantOption> options = new ArrayList<>();
        if (r.getVariantAxes() != null) {
            for (var ax : r.getVariantAxes()) {
                if (ax.getName() == null || ax.getName().isBlank()) {
                    continue;
                }
                List<IngestVariantValue> vals = new ArrayList<>();
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
            LinkedHashMap<String, LinkedHashSet<String>> derived = new LinkedHashMap<>();
            for (var v : r.getVariants()) {
                if (v.getOptionValues() == null) {
                    continue;
                }
                for (var e : v.getOptionValues().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                        continue;
                    }
                    derived.computeIfAbsent(e.getKey().trim(), k -> new LinkedHashSet<>())
                            .add(e.getValue().trim());
                }
            }
            for (var en : derived.entrySet()) {
                List<IngestVariantValue> vals = new ArrayList<>();
                int vp = 0;
                for (String val : en.getValue()) {
                    vals.add(new IngestVariantValue(val, vp++, null));
                }
                options.add(new IngestVariantOption(en.getKey(), options.size(), vals));
            }
        }
        // Variantes/SKU desde el JSON; si no vienen, una variante por defecto.
        List<IngestVariant> variants = new ArrayList<>();
        if (r.getVariants() != null && !r.getVariants().isEmpty()) {
            int vi = 0;
            for (var v : r.getVariants()) {
                String sku = (v.getSku() != null && !v.getSku().isBlank()) ? v.getSku()
                        : externalId + "-" + (vi + 1);
                variants.add(new IngestVariant(sku, sku, esTitle,
                        v.getPrice() != null ? v.getPrice() : price, v.getStock() != null ? v.getStock() : 0,
                        v.getImageUrl(), v.getOptionValues() != null ? v.getOptionValues() : Map.of()));
                vi++;
            }
        } else {
            // DROP-680: sin variantes declaradas no se inventa inventario (stock real desconocido = 0).
            variants.add(new IngestVariant(externalId + "-DEF", externalId + "-DEF", esTitle, price, 0, null,
                    Map.of()));
        }
        // Tiered pricing (DROP-669): se persisten SOLO los tramos reales que declara el proveedor.
        // Si no hay tramos no se inventa ninguno: la ficha muestra únicamente el precio unitario.
        List<IngestPriceTier> tiers = new ArrayList<>();
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
                (r.getSourceUrl() != null && !r.getSourceUrl().isBlank()) ? r.getSourceUrl().trim()
                        : "https://detail.1688.com/offer/" + externalId + ".html",
                supplierId, cat.getId(), images, options, variants, tiers);
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
            List<BulkProductDtoIn.BulkReview> reviews) {
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
            var e = ProductReviewEntity.builder()
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
    private void applyLogistics(ProductEntity p, BulkProductDtoIn r) {
        // Envío e IVA (CNY): obligatorios en la carga; se suman al total SIN margen (ver PricingService).
        p.setShippingCny(r.getShippingCny());
        p.setIvaCny(r.getIvaCny());
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
                p.setRating(BigDecimal.valueOf((double) weighted / total)
                        .setScale(2, RoundingMode.HALF_UP));
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
            Map<String, BulkProductDtoIn.BulkVariant> bySku =
                    new HashMap<>();
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
            Map<String, Map<String, String>> byValue = new HashMap<>();
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
                                vv.getTranslations().add(VariantValueTranslationEntity
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
        // Contenido por idioma ILIMITADO: el writer fija es/en/pt/zh desde los campos fijos; aquí se
        // upsertan las traducciones del mapa `translations` para CUALQUIER idioma (incl. fr/de/ja/…),
        // sin duplicar (si ya existe el idioma, se actualiza). El mapa tiene prioridad (más explícito).
        if (r.getTranslations() != null && !r.getTranslations().isEmpty()) {
            for (var e : r.getTranslations().entrySet()) {
                String lang = e.getKey() != null ? e.getKey().trim().toLowerCase() : null;
                var tr = e.getValue();
                if (lang == null || lang.isEmpty() || tr == null
                        || (tr.getTitle() == null || tr.getTitle().isBlank())) {
                    continue;
                }
                var existing = p.getTranslations().stream()
                        .filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst().orElse(null);
                if (existing == null) {
                    existing = ProductTranslationEntity
                            .builder().product(p).language(lang).provider("bulk").build();
                    p.getTranslations().add(existing);
                }
                existing.setTitle(tr.getTitle().trim());
                String sd = tr.getShortDescription() != null && !tr.getShortDescription().isBlank()
                        ? tr.getShortDescription().trim()
                        : (tr.getDescription() != null ? tr.getDescription().trim() : tr.getTitle().trim());
                existing.setShortDescription(sd != null && sd.length() > 2000 ? sd.substring(0, 2000) : sd);
                existing.setDescription(tr.getDescription() != null ? tr.getDescription().trim() : sd);
            }
        }
        // Las traducciones (título + descripción por idioma) las fija el writer dentro de su transacción.
        // DROP-679: como el writer publica el producto (status ACTIVE), generamos aquí el SEO por idioma
        // a partir de esas traducciones reales (las colecciones ya están adjuntas a la entidad gestionada).
        generateSeoMetadata(p);
    }

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public BulkResultDtoOut bulkCreateCategories(
            List<BulkCategoryDtoIn> rows) {
        int created = 0, failed = 0;
        List<String> errors = new ArrayList<>();
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
                        Map.of("es", r.getNameEs(), "en", en, "pt", pt)));
                created++;
            } catch (Exception e) {
                failed++;
                errors.add("Fila " + (i + 1) + ": " + ErrorMessages.humanize(e));
            }
        }
        return new BulkResultDtoOut(created, failed, errors);
    }

    /**
     * DROP-677: resuelve la categoría interna del producto al importar. Prioridad: (1) categorySlug
     * interno explícito; (2) mapeo por id de 1688; (3) mapeo por nombre de 1688. Si nada resuelve, se
     * rechaza la fila (no se inventa una categoría).
     */
    private CategoryEntity resolveBulkCategory(BulkProductDtoIn r) {
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
                Category1688MappingEntity::new);
        m.setExternal1688Id(external1688Id.trim());
        m.setExternal1688Name(external1688Name != null && !external1688Name.isBlank() ? external1688Name.trim() : null);
        m.setCategory(cat);
        return category1688MappingRepository.save(m).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category1688MappingDtoOut> listCategory1688Mappings() {
        return category1688MappingRepository.findAll().stream()
                .map(m -> new Category1688MappingDtoOut(m.getId(),
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
    public List<CategoryAttributeSchemaDtoOut> listCategoryAttributeSchema(
            UUID categoryId) {
        return categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(categoryId).stream()
                .map(s -> new CategoryAttributeSchemaDtoOut(s.getId(),
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
                CategoryAttributeSchemaEntity::new);
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
                .map(CategoryTranslationEntity::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(c.getNameZh() != null ? c.getNameZh() : c.getSlug());
    }

    private UUID resolveBulkSupplier(List<SupplierEntity> suppliers, String supplierExternalId,
            String supplierName) {
        if (supplierExternalId != null && !supplierExternalId.isBlank()) {
            String ext = supplierExternalId.trim();
            var existing = supplierRepository.findBySourceAndExternalId("1688", ext);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
            // El proveedor de 1688 aún no existe: se crea con ese externalId y el nombre disponible
            // (supplierName/manufacturer) en vez de rechazar la fila. Así el import es autosuficiente.
            String name = (supplierName != null && !supplierName.isBlank()) ? supplierName.trim()
                    : ("Proveedor " + ext);
            String extId = ext.length() > 100 ? ext.substring(0, 100) : ext;
            return supplierRepository.save(SupplierEntity.builder().source("1688").externalId(extId).name(name).country("CN")
                    .verified(false).trustPass(false).build()).getId();
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
                return supplierRepository.save(SupplierEntity.builder().source("1688").externalId(ext).name(name).country("CN")
                        .verified(false).trustPass(false).build()).getId();
            });
        }
        if (suppliers.isEmpty())
            throw new BusinessException("No hay proveedores; crea uno antes de importar productos");
        return suppliers.get(0).getId();
    }

}
