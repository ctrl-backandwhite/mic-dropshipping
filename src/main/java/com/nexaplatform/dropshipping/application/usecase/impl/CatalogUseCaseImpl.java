package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTranslation;
import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.exception.ArgumentException;
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
import com.nexaplatform.dropshipping.application.service.BulkProductFields;
import com.nexaplatform.dropshipping.application.service.CatalogReindexRunner;
import com.nexaplatform.dropshipping.application.service.BulkProductRules;
import com.nexaplatform.dropshipping.application.service.BulkProductStructure;
import com.nexaplatform.dropshipping.application.service.ProductSeoMetadata;
import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
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
import java.util.Locale;
import java.util.Arrays;
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
import java.util.regex.Pattern;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String CATEGORY_NOT_FOUND = "Category not found: ";
    private static final String PRODUCT_NOT_FOUND = "Product not found: ";
    private static final String PRODUCT_NOT_FOUND_2 = "Product not found";
    private static final String VARIANT_NOT_FOUND = "Variant not found";
    private static final String VARIANT_VALUE = "Variant value";
    private static final String GALLERY = "GALLERY";

    private static final Slugify SLUG = Slugify.builder().lowerCase(true).build();

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CategoryRepository categoryRepository;
    /** Completa HS code, material, uso, batería y medidas de paquete desde el perfil de la categoría. */
    private final CustomsProfileService customsProfileService;
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
    /** Ejecutor del reindexado completo en segundo plano (evita el timeout del proxy/edge). */
    private final CatalogReindexRunner reindexRunner;
    /** DROP-677: mapeo de categorías de 1688 → categoría interna, usado al resolver la fila de carga. */
    private final Category1688MappingRepository category1688MappingRepository;
    /** DROP-670: esquema de atributos obligatorios por categoría, validado en cada alta masiva. */
    private final CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;
    /** Reseñas reales que vienen en la carga masiva. */
    private final ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

    @PersistenceContext
    private EntityManager em;

    // Auto-referencia POR EL PROXY. Los métodos de lote (runBatch, bulkCreateCategories) documentan que
    // cada elemento va en su propia transacción, pero llamarlos con this los saltaba: la autoinvocación no
    // pasa por el proxy de Spring, así que el @Transactional del método invocado no se aplicaba y el lote
    // entero corría sin transacción propia. @Lazy evita el ciclo de construcción consigo mismo.
    private CatalogUseCase self;

    // El escritor ingesta a través de ESTE mismo caso de uso, así que por constructor el ciclo no se
    // podría resolver; @Lazy difiere la resolución hasta el primer uso.
    private CatalogFillWriter catalogFillWriter;

    // Inyección por método (no por campo): ni la auto-referencia ni el escritor pueden entrar por el
    // constructor sin cerrar un ciclo de creación, y el setter deja la dependencia explícita en la API
    // de la clase en vez de escondida en un campo anotado.
    @Autowired
    public void setSelf(@Lazy CatalogUseCase self) {
        this.self = self;
    }

    @Autowired
    public void setCatalogFillWriter(@Lazy CatalogFillWriter catalogFillWriter) {
        this.catalogFillWriter = catalogFillWriter;
    }

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

    // Las cachés de categorías se invalidan también aquí: el alta delegaba en upsertCategory() con this y
    // la autoinvocación no pasa por el proxy, así que sus @CacheEvict no llegaban a ejecutarse y la
    // categoría recién creada no salía en el escaparate hasta que caducaba la caché.
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public CategoryEntity createCategoryRejectingDuplicateSlug(IngestCategoryRequest req) {
        if (categoryRepository.findBySlug(req.slug()).isPresent()) {
            throw new BusinessException("Ya existe una categoría con slug \"" + req.slug() + "\"");
        }
        return upsertCategoryInternal(req);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public CategoryEntity upsertCategory(IngestCategoryRequest req) {
        return upsertCategoryInternal(req);
    }

    /** Alta/actualización real de la categoría; las anotaciones viven en los métodos públicos de entrada. */
    private CategoryEntity upsertCategoryInternal(IngestCategoryRequest req) {
        CategoryEntity entity = categoryRepository.findBySlug(req.slug())
                .orElseGet(() -> CategoryEntity.builder().slug(req.slug()).active(true).build());
        entity.setNameZh(req.nameZh());
        entity.setSource(req.source() != null ? req.source() : "1688");
        entity.setExternalId(req.externalId());
        entity.setPosition(req.position());
        entity.setIcon(req.icon());
        if (req.parentId() != null) {
            // Con orElse(null) un padre inexistente NO se ignoraba: borraba el padre que la categoría ya
            // tenía, y la rama entera saltaba a la raíz del árbol del escaparate.
            entity.setParent(categoryRepository.findById(req.parentId())
                    .orElseThrow(() -> new NotFoundException("Parent category")));
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

        // Las colecciones hijas se REEMPLAZAN enteras en cada ingesta: es lo que hace que reimportar sea
        // idempotente en vez de ir acumulando imágenes y variantes duplicadas.
        replaceImages(product, req);
        // DROP variant-images: una variante o un valor sin imagen propia cae a la principal del producto,
        // para que el comprador vea siempre una foto coherente al elegir.
        String mainImageUrl = mainImageUrlOf(req.images());
        replaceVariantOptions(product, req, mainImageUrl);
        replaceVariants(product, req, mainImageUrl);

        product = productJpaRepository.save(product);
        replacePriceTiers(product, req);
        publishIngested(product);

        log.info("Upserted product {} ({} - {})", product.getId(), product.getSource(), product.getExternalId());
        return product;
    }

    /** Imágenes del producto, todas PENDING de espejar a nuestro almacenamiento. */
    private void replaceImages(ProductEntity product, IngestProductRequest req) {
        product.getImages().clear();
        if (req.images() == null) {
            return;
        }
        for (IngestImage img : req.images()) {
            product.getImages().add(ProductImageEntity.builder().product(product).position(img.position())
                    .role(img.role() != null ? img.role() : GALLERY).sourceUrl(img.sourceUrl())
                    .mirrorStatus(MirrorStatus.PENDING).build());
        }
    }

    /** Ejes de variación (Color, Talla) con sus valores y la foto de cada uno. */
    private void replaceVariantOptions(ProductEntity product, IngestProductRequest req, String mainImageUrl) {
        product.getVariantOptions().clear();
        if (req.options() == null) {
            return;
        }
        for (IngestVariantOption optReq : req.options()) {
            VariantOptionEntity opt = VariantOptionEntity.builder().product(product).nameZh(optReq.nameZh())
                    .position(optReq.position()).build();
            if (optReq.values() != null) {
                for (IngestVariantValue v : optReq.values()) {
                    opt.getValues().add(VariantValueEntity.builder().option(opt).valueZh(v.valueZh())
                            .position(v.position())
                            .imageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl).build());
                }
            }
            product.getVariantOptions().add(opt);
        }
    }

    /** Variantes comprables. Sin stock declarado se guarda 0: el stock real no se inventa. */
    private void replaceVariants(ProductEntity product, IngestProductRequest req, String mainImageUrl) {
        product.getVariants().clear();
        if (req.variants() == null) {
            return;
        }
        for (IngestVariant v : req.variants()) {
            product.getVariants().add(ProductVariantEntity.builder().product(product).externalId(v.externalId())
                    .sku(v.sku()).title(v.title()).price(v.price()).stock(v.stock() != null ? v.stock() : 0)
                    .imageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl)
                    .options(v.options()).active(true).build());
        }
    }

    /** Tramos de precio: van en su propia tabla, así que se borran y recrean aparte del producto. */
    private void replacePriceTiers(ProductEntity product, IngestProductRequest req) {
        if (req.priceTiers() == null) {
            return;
        }
        priceTierRepository.findByProductIdOrderByMinQtyAsc(product.getId()).forEach(priceTierRepository::delete);
        for (IngestPriceTier t : req.priceTiers()) {
            priceTierRepository.save(ProductPriceTierEntity.builder().product(product).minQty(t.minQty())
                    .maxQty(t.maxQty()).unitPrice(t.unitPrice())
                    .currency(t.currency() != null ? t.currency() : "CNY").build());
        }
    }

    /**
     * Avisa al indexador de que el producto cambió. El id puede faltar todavía —JPA lo asigna al volcar, y
     * en un test con dobles puros puede no llegar nunca—, y en ese caso no se publica en vez de reventar.
     */
    private void publishIngested(ProductEntity product) {
        if (product.getId() == null) {
            return;
        }
        kafkaTemplate.send(NexaTopics.PRODUCT_INGESTED, product.getId().toString(), new ProductIngestedEvent(
                product.getId(), product.getSlug(), product.getSource(), product.getExternalId()));
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
            return pageProducts(st, pageable, language);
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
        return pageProducts(status, pageable, language);
    }

    /**
     * Página de productos sin anotación transaccional, para que la reutilice el listado de admin de esta
     * misma clase: la autoinvocación no pasa por el proxy y el {@code @Transactional} del método invocado
     * no se aplicaba. La transacción la abre el método público de entrada.
     */
    private Page<ProductSummaryView> pageProducts(ProductStatus status, Pageable pageable, String language) {
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
            throw new NotFoundException(PRODUCT_NOT_FOUND + id);
        }
        return model;
    }

    @Override
    @Transactional(readOnly = true)
    public Product getProductModelBySlug(String slug) {
        return productRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + slug));
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
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "#slug + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingChannelHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingCountryHolder).get() + ':' + T(com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils).isAdmin()")
    public ProductDetailView getProductBySlug(String slug, String language) {
        ProductEntity p = productJpaRepository.findWithDetailsBySlug(slug)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + slug));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CACHE_PRODUCT_DETAIL, key = "'id:' + #id + ':' + #language + ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingChannelHolder).get() + ':' + T(com.nexaplatform.dropshipping.application.service.PricingCountryHolder).get() + ':' + T(com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils).isAdmin()")
    public ProductDetailView getProductById(UUID id, String language) {
        return detailById(id, language);
    }

    /**
     * Ficha por id sin anotaciones: la reutiliza la búsqueda por identificador externo de esta misma
     * clase. La autoinvocación no pasa por el proxy, así que ni el {@code @Cacheable} ni el
     * {@code @Transactional} del método invocado se aplicaban; ahora viven solo en el método de entrada.
     */
    private ProductDetailView detailById(UUID id, String language) {
        ProductEntity p = productJpaRepository.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        forceLoadCollections(p);
        return productMapper.toDetail(p, language, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDetailView getProductByExternal(String source, String externalId, String language) {
        ProductEntity p = productJpaRepository.findBySourceAndExternalId(source, externalId)
                .orElseThrow(() -> new NotFoundException("Product"));
        return detailById(p.getId(), language);
    }

    /** Trigger lazy collections while still inside the transaction (open-in-view=false). */
    private void forceLoadCollections(ProductEntity p) {
        p.getImages().size();
        p.getVariants().size();
        p.getVariantOptions().forEach(o -> o.getValues().forEach(v -> v.getTranslations().size()));
        p.getTranslations().size();
    }

    // Esta variante también invalida las cachés de producto: delegaba en updateStatus(UUID, ProductStatus)
    // con this y la autoinvocación no pasa por el proxy, así que sus @CacheEvict no se ejecutaban y el
    // escaparate seguía sirviendo el producto con el estado anterior hasta que la entrada caducaba.
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void updateStatus(UUID id, String status) {
        // valueOf crudo daba un 500 con un estado desconocido, mientras el listado del mismo panel tolera
        // basura. Aquí sí hay que rechazarlo —cambiar el estado a «lo que sea» no significa nada— pero como
        // 400 y diciendo cuáles valen.
        ProductStatus parsed;
        try {
            parsed = ProductStatus.valueOf(status == null ? "" : status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ArgumentException("Estado de producto no válido: " + status + ". Valores admitidos: "
                    + Arrays.toString(ProductStatus.values()));
        }
        applyStatus(id, parsed);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void updateStatus(UUID id, ProductStatus status) {
        applyStatus(id, status);
    }

    /** Cambio de estado real; las anotaciones de caché y transacción viven en los métodos de entrada. */
    private void applyStatus(UUID id, ProductStatus status) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        p.setStatus(status);
        // DROP-679: al publicar se generan los metadatos SEO por idioma a partir del contenido real
        // (título/descripción ya traducidos), sin sobrescribir los que el operador haya definido.
        if (status == ProductStatus.ACTIVE) {
            ProductSeoMetadata.generate(p);
        }
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
    }

    /** DROP-679: rellena meta_title/meta_description (solo si están vacíos) desde el contenido real. */

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deletePriceTier(UUID productId, int minQty) {
        if (!productJpaRepository.existsById(productId))
            throw new NotFoundException(PRODUCT_NOT_FOUND + productId);
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        applyQuickEditScalars(p, req);
        applyQuickEditVideo(p, req);
        applyQuickEditTranslation(p, req, lang);
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    /** Campos sueltos de la ficha: sólo se toca lo que el admin manda, un null es "no lo edito". */
    private void applyQuickEditScalars(ProductEntity p, AdminProductQuickEditDtoIn req) {
        if (req.getBrand() != null) {
            p.setBrand(req.getBrand());
        }
        if (req.getBasePrice() != null) {
            p.setBasePrice(req.getBasePrice());
        }
        if (Texts.has(req.getCurrency())) {
            p.setCurrency(req.getCurrency());
        }
        if (req.getMoq() != null) {
            p.setMoq(req.getMoq());
        }
        // Envío e IVA se cargan al importar, pero hasta ahora no había forma de corregirlos sin
        // reimportar el producto entero: el PUT aceptaba el campo y lo descartaba en silencio.
        if (req.getShippingCny() != null) {
            p.setShippingCny(req.getShippingCny());
        }
        if (req.getIvaCny() != null) {
            p.setIvaCny(req.getIvaCny());
        }
        // Verificación manual del admin (checkbox del listado): true = revisado OK, false = pendiente/reimportar.
        if (req.getVerified() != null) {
            p.setVerified(req.getVerified());
        }
        // Reasignar categoría desde el admin (selector de la ficha). Se resuelve por id y se valida que exista.
        if (req.getCategoryId() != null) {
            p.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new NotFoundException(CATEGORY_NOT_FOUND + req.getCategoryId())));
        }
    }

    /** DROP-673: edición del vídeo real del producto desde el admin (cadena vacía lo elimina). */
    private void applyQuickEditVideo(ProductEntity p, AdminProductQuickEditDtoIn req) {
        if (req.getVideoUrl() == null) {
            return;
        }
        String url = req.getVideoUrl().trim();
        p.setVideoUrl(url.isEmpty() ? null : url);
        // Borrar el vídeo principal no significa que el producto se quede sin vídeo: puede quedar alguno
        // en la lista secundaria, y en ese caso la ficha lo sigue anunciando.
        p.setHasVideo(!url.isEmpty() || (p.getVideoUrls() != null && !p.getVideoUrls().isEmpty()));
    }

    /**
     * Contenido del idioma activo (título, descripciones y SEO). Se edita la traducción, NUNCA el
     * {@code title_zh} canónico: ese es el dato del proveedor y es el que empareja las reimportaciones.
     */
    private void applyQuickEditTranslation(ProductEntity p, AdminProductQuickEditDtoIn req, String lang) {
        if (!touchesTranslation(req)) {
            return;
        }
        ProductTranslationEntity tr = translationFor(p, lang);
        if (Texts.has(req.getTitle())) {
            tr.setTitle(req.getTitle());
        }
        if (req.getShortDescription() != null) {
            tr.setShortDescription(req.getShortDescription());
        }
        if (req.getDescription() != null) {
            tr.setDescription(req.getDescription());
        }
        // DROP-688: edición manual del SEO (meta título/descripción) del idioma activo.
        if (req.getMetaTitle() != null) {
            tr.setMetaTitle(req.getMetaTitle().isBlank() ? null : req.getMetaTitle().trim());
        }
        if (req.getMetaDescription() != null) {
            tr.setMetaDescription(req.getMetaDescription().isBlank() ? null : req.getMetaDescription().trim());
        }
    }

    /** ¿La edición toca algún campo traducible? Si no, no se crea una traducción vacía para ese idioma. */
    private static boolean touchesTranslation(AdminProductQuickEditDtoIn req) {
        return Texts.has(req.getTitle()) || req.getShortDescription() != null || req.getDescription() != null
                || req.getMetaTitle() != null || req.getMetaDescription() != null;
    }

    /** Traducción del idioma activo, creándola si el producto todavía no la tiene. */
    private static ProductTranslationEntity translationFor(ProductEntity p, String lang) {
        return p.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst()
                .orElseGet(() -> {
                    ProductTranslationEntity created = ProductTranslationEntity.builder().product(p).language(lang)
                            .provider("admin").build();
                    p.getTranslations().add(created);
                    return created;
                });
    }

    @Override
    @Transactional
    public ProductDetailView duplicateProduct(UUID id, String lang) {
        ProductEntity src = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
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
        for (ProductTranslationEntity tr : src.getTranslations()) {
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
    public ReindexStatus startReindex() {
        // tryAcquire() marca "en curso" de forma atómica; si ya había uno, no se lanza otro.
        if (!reindexRunner.tryAcquire()) {
            return new ReindexStatus(true, false, reindexRunner.lastIndexed());
        }
        // Cruce de bean (runner distinto): así surte efecto el @Async y la petición vuelve al instante.
        reindexRunner.runAsync();
        return new ReindexStatus(true, true, reindexRunner.lastIndexed());
    }

    @Override
    public ReindexStatus reindexStatus() {
        return new ReindexStatus(reindexRunner.isRunning(), false, reindexRunner.lastIndexed());
    }

    @Override
    @Transactional
    public int backfillVariantAxes() {
        int filled = 0;
        for (ProductEntity p : productJpaRepository.findAll()) {
            if (backfillAxesOf(p)) {
                filled++;
            }
        }
        if (filled > 0) {
            log.info("::> [VARIANTS] Backfilled variation axes from variants for {} products", filled);
        }
        return filled;
    }

    /**
     * Reconstruye los ejes (Color/Talla) de un producto a partir de las opciones de sus variantes.
     *
     * <p>Sólo actúa sobre el producto que NO tiene ejes pero SÍ variantes: si ya los tiene son los que
     * declaró el proveedor y pisarlos con los derivados perdería nombres y orden reales.
     *
     * @return true si se rellenaron ejes (para contarlo en el resumen del backfill)
     */
    private boolean backfillAxesOf(ProductEntity p) {
        if (!p.getVariantOptions().isEmpty() || p.getVariants() == null || p.getVariants().isEmpty()) {
            return false;
        }
        Map<String, LinkedHashSet<String>> derived = axesFromVariantOptions(p);
        if (derived.isEmpty()) {
            return false;
        }
        int position = 0;
        for (Map.Entry<String, LinkedHashSet<String>> axis : derived.entrySet()) {
            p.getVariantOptions().add(buildAxis(p, axis.getKey(), axis.getValue(), position++));
        }
        productJpaRepository.save(p);
        productIndexer.indexProduct(p.getId());
        return true;
    }

    /**
     * Nombre de eje → valores distintos, conservando el orden de aparición en las variantes (por eso
     * LinkedHashMap/LinkedHashSet: ese orden es el que verá el usuario en el selector de la ficha).
     */
    private static Map<String, LinkedHashSet<String>> axesFromVariantOptions(ProductEntity p) {
        Map<String, LinkedHashSet<String>> derived = new LinkedHashMap<>();
        for (ProductVariantEntity v : p.getVariants()) {
            if (v.getOptions() == null) {
                continue;
            }
            for (Map.Entry<String, String> e : v.getOptions().entrySet()) {
                if (Texts.has(e.getKey()) && Texts.has(e.getValue())) {
                    derived.computeIfAbsent(e.getKey().trim(), k -> new LinkedHashSet<>()).add(e.getValue().trim());
                }
            }
        }
        return derived;
    }

    /** Eje derivado: el nombre no está traducido, así que se guarda igual como canónico y como visible. */
    private static VariantOptionEntity buildAxis(ProductEntity p, String name, LinkedHashSet<String> values,
            int position) {
        VariantOptionEntity opt = new VariantOptionEntity();
        opt.setProduct(p);
        opt.setNameZh(name);
        opt.setName(name);
        opt.setPosition(position);
        int valuePosition = 0;
        for (String value : values) {
            VariantValueEntity vv = new VariantValueEntity();
            vv.setOption(opt);
            vv.setValueZh(value);
            vv.setPosition(valuePosition++);
            opt.getValues().add(vv);
        }
        return opt;
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
                                || (!zh && (ProductSeoMetadata.hasCjk(tr.getMetaTitle()) || ProductSeoMetadata.hasCjk(tr.getMetaDescription()))));
            });
            if (missing) {
                ProductSeoMetadata.generate(p);
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND_2));
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
                .orElseThrow(() -> new NotFoundException(VARIANT_NOT_FOUND));
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
                .orElseThrow(() -> new NotFoundException(VARIANT_NOT_FOUND));
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
        VariantValueEntity v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
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
        VariantValueEntity v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
        VariantOptionEntity opt = v.getOption();
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
        VariantValueEntity v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
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
        VariantValueEntity v = variantValueRepository.findById(valueId).orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
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
                .orElseThrow(() -> new NotFoundException(VARIANT_NOT_FOUND));
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND_2));
        int nextPos = product.getImages().stream().mapToInt(ProductImageEntity::getPosition).max().orElse(-1) + 1;
        boolean asMain = product.getImages().isEmpty() || "MAIN".equalsIgnoreCase(role);
        if (asMain) {
            product.getImages().forEach(i -> {
                if ("MAIN".equalsIgnoreCase(i.getRole())) {
                    i.setRole(GALLERY);
                }
            });
        }
        // Si la URL ya apunta a NUESTRO storage (S3/MinIO) está lista → MIRRORED. Si es externa
        // (1688/alicdn u otro CDN), la dejamos PENDING para que el ImageMirrorService la descargue y la
        // suba a S3 (y deje de depender del hotlink). Persist vía el repo para devolver el id generado.
        boolean alreadyOurs = url != null && objectStorage.publicUrl() != null
                && !objectStorage.publicUrl().isBlank() && url.startsWith(objectStorage.publicUrl());
        ProductImageEntity img = ProductImageEntity.builder().product(product).position(nextPos)
                .role(asMain ? "MAIN" : GALLERY).sourceUrl(url).cdnUrl(alreadyOurs ? url : null)
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND_2));
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
                img.setRole(pos == 0 ? "MAIN" : GALLERY);
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
        int created = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        List<UUID> createdIds = new ArrayList<>();
        List<SupplierEntity> suppliers = supplierRepository.findAll();
        for (int i = 0; i < rows.size(); i++) {
            BulkProductDtoIn r = rows.get(i);
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
    public List<BulkProductDtoIn> exportProducts(int from, int to, Instant createdFrom, Instant createdTo) {
        int safeFrom = Math.max(1, from);
        int safeTo = Math.max(safeFrom, to);
        int offset = safeFrom - 1;
        int limit = safeTo - safeFrom + 1;
        // El filtro por fecha de carga se añade SOLO si viene informado: pasar un parámetro null a un
        // "(:cf IS NULL OR ...)" hace que Postgres no pueda inferir el tipo del bind ("could not determine
        // data type of parameter"). Construyendo el WHERE condicional se evita el bind nulo por completo.
        StringBuilder jpql = new StringBuilder("SELECT p FROM ProductEntity p WHERE 1 = 1");
        if (createdFrom != null) {
            jpql.append(" AND p.ingestedAt >= :cf");
        }
        if (createdTo != null) {
            jpql.append(" AND p.ingestedAt < :ct");
        }
        jpql.append(" ORDER BY p.id ASC");
        TypedQuery<ProductEntity> query = em.createQuery(jpql.toString(), ProductEntity.class);
        if (createdFrom != null) {
            query.setParameter("cf", createdFrom);
        }
        if (createdTo != null) {
            query.setParameter("ct", createdTo);
        }
        List<ProductEntity> products = query.setFirstResult(offset).setMaxResults(limit).getResultList();
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
    public ProductExportBatch exportBatchAfter(UUID afterId, int limit, Instant createdFrom, Instant createdTo) {
        int safeLimit = Math.clamp(limit, 1, 1000);
        // Keyset pagination by id. A native query with an explicit uuid cast is used because Hibernate does
        // not reliably translate the JPQL "p.id > :afterId" comparison on a UUID column (it silently returns
        // no rows past a point), which truncated the stream. Native SQL uses Postgres' native uuid ordering.
        // El rango opcional por fecha de carga (ingested_at) se pasa como texto ISO y se castea a timestamptz.
        @SuppressWarnings("unchecked")
        List<ProductEntity> products = em.createNativeQuery(
                "SELECT * FROM product WHERE (CAST(:afterId AS uuid) IS NULL OR id > CAST(:afterId AS uuid)) "
                        + "AND (CAST(:cf AS timestamptz) IS NULL OR ingested_at >= CAST(:cf AS timestamptz)) "
                        + "AND (CAST(:ct AS timestamptz) IS NULL OR ingested_at < CAST(:ct AS timestamptz)) "
                        + "ORDER BY id ASC LIMIT :lim", ProductEntity.class)
                .setParameter("afterId", afterId != null ? afterId.toString() : null)
                .setParameter("cf", createdFrom != null ? createdFrom.toString() : null)
                .setParameter("ct", createdTo != null ? createdTo.toString() : null)
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
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
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
    public long countProducts(Instant createdFrom, Instant createdTo) {
        // Filtro condicional (ver exportProducts): evita el bind nulo sin tipo que Postgres rechaza.
        StringBuilder jpql = new StringBuilder("SELECT COUNT(p) FROM ProductEntity p WHERE 1 = 1");
        if (createdFrom != null) {
            jpql.append(" AND p.ingestedAt >= :cf");
        }
        if (createdTo != null) {
            jpql.append(" AND p.ingestedAt < :ct");
        }
        TypedQuery<Long> query = em.createQuery(jpql.toString(), Long.class);
        if (createdFrom != null) {
            query.setParameter("cf", createdFrom);
        }
        if (createdTo != null) {
            query.setParameter("ct", createdTo);
        }
        return query.getSingleResult();
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
    public BulkOutcome bulkDeleteProducts(List<UUID> ids) {
        return runBatch(ids, self::deleteProduct);
    }

    @Override
    public BulkOutcome bulkUpdateStatus(List<UUID> ids, String status) {
        return runBatch(ids, id -> self.updateStatus(id, status));
    }

    /**
     * Aplica una acción a cada id y acumula los fallos sin cortar el lote. Cada elemento va en su propia
     * transacción —la abre el método invocado A TRAVÉS DE {@code self}, no con {@code this}: por
     * autoinvocación el proxy no interviene y no se abriría ninguna—, así que un fallo no arrastra a los
     * que ya pasaron.
     */
    private BulkOutcome runBatch(List<UUID> ids, java.util.function.Consumer<UUID> action) {
        int succeeded = 0;
        List<String> errors = new ArrayList<>();
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            try {
                action.accept(id);
                succeeded++;
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ErrorMessages.humanize(ex));
            }
        }
        return new BulkOutcome(succeeded, errors);
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void deleteProduct(UUID id) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND_2));
        Long orders = jdbcTemplate.queryForObject("SELECT count(*) FROM order_item WHERE product_id = ?", Long.class,
                id);
        if (orders != null && orders > 0) {
            throw new BusinessException("No se puede eliminar: el producto tiene pedidos. Archívalo en su lugar.");
        }
        // Flat children without JPA cascade are removed first; the mapped collections
        // (images/variants/translations/variantOptions) cascade on the entity delete.
        for (String table : PRODUCT_CHILD_TABLES) {
            // NOSONAR java:S2077 — lo único concatenado es el nombre de tabla, que sale de la constante
            // PRODUCT_CHILD_TABLES del propio código; el valor va como parámetro.
            jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", id); // NOSONAR
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
        BulkTranslation tr = m.get(lang);
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
        BulkProductRules.assertRequiredAttributes(r,
                categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(cat.getId()), cat.getSlug());
        // El proveedor se toma de supplierName; si no, del fabricante (manufacturer). Solo si no hay
        // ninguno se usa el primero por defecto.
        String supName = Texts.firstNonBlankOr(r.getManufacturer(), r.getSupplierName());
        UUID supplierId = resolveBulkSupplier(suppliers, r.getSupplierExternalId(), supName);
        fillCanonicalContentFromTranslations(r);
        String esTitle = r.getTitleEs();
        // DROP-682: el título es obligatorio en al menos un idioma; mensaje claro (no genérico).
        BulkProductRules.assertTitle(esTitle);
        String enTitle = Texts.firstNonBlankOr(esTitle, r.getTitleEn());
        String zhTitle = Texts.firstNonBlankOr(esTitle, r.getTitleZh());
        String ptTitle = Texts.firstNonBlankOr(esTitle, r.getTitlePt());
        String esDesc = Texts.firstNonBlankOr(esTitle, r.getDescriptionEs());
        // DROP-680: el precio es dato real obligatorio; no se inventa. Envío e IVA (CNY) también, porque
        // sin ellos no se puede calcular el total (base×margen + iva + envío).
        BigDecimal price = BulkProductRules.resolvePrice(r, esTitle);
        BulkProductRules.assertShippingAndVat(r, esTitle);
        // external_id es varchar(120): con títulos largos el slug autogenerado lo desbordaba.
        String externalId = BulkProductRules.externalIdOf(r, esTitle, SLUG::slugify, System.nanoTime());
        deleteRebuiltChildRows(r, externalId);
        List<IngestImage> images = ingestImagesOf(r, esTitle);
        // Ejes de variación (Color/Talla), variantes comprables y tramos de precio: se derivan de la
        // fila sin inventar nada (ver BulkProductStructure).
        List<IngestVariantOption> options = BulkProductStructure.variantOptionsOf(r);
        List<IngestVariant> variants = BulkProductStructure.variantsOf(r, externalId, esTitle, price);
        List<IngestPriceTier> tiers = BulkProductStructure.priceTiersOf(r, price);
        // DROP-680: rating, recompra y reseñas NO se inventan. Si el proveedor no los declara quedan
        // nulos/0; el desglose real (ratingBreakdown) se persiste y deriva reviewCount/rating en applyLogistics.
        IngestProductRequest req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc,
                r.getManufacturer(), r.getMoq() != null ? r.getMoq() : 1, price, "CNY", r.getWeightGrams(),
                r.getMonthlySales() != null ? r.getMonthlySales() : 0, null,
                r.getRating(), 0,
                Texts.firstNonBlankOr("https://detail.1688.com/offer/" + externalId + ".html",
                        r.getSourceUrl() != null ? r.getSourceUrl().trim() : null),
                supplierId, cat.getId(), images, options, variants, tiers);
        // El writer corre @Transactional: aplica títulos+descripciones por idioma y la logística
        // (vía el hook) sobre la entidad gestionada, evitando LazyInitialization.
        UUID id = catalogFillWriter.write(req, esTitle, enTitle, ptTitle, zhTitle, esDesc, r.getDescriptionEn(),
                r.getDescriptionPt(), r.getDescriptionZh(), p -> applyLogistics(p, r));
        applyRequestedDraftStatus(id, r);
        createBulkReviews(id, r.getReviews());
        return id;
    }

    /**
     * Idiomas ilimitados: si el contenido viene en el mapa {@code translations}, rellena los campos
     * canónicos es/en/pt/zh donde falten, porque de ellos salen el slug, la validación y lo que escribe
     * el writer; el resto de idiomas del mapa se upsertan después en {@link #applyLogistics}.
     *
     * <p>Si no hay 'es' explícito se toma el primer idioma con título como canónico: el alta exige
     * titleEs y, sin este respaldo, una fila perfectamente válida en inglés se rechazaría.
     */
    private void fillCanonicalContentFromTranslations(BulkProductDtoIn r) {
        Map<String, BulkProductDtoIn.BulkTranslation> m = r.getTranslations();
        if (m == null || m.isEmpty()) {
            return;
        }
        mergeTranslationField("es", m, r::getTitleEs, r::setTitleEs, r::getDescriptionEs, r::setDescriptionEs);
        mergeTranslationField("en", m, r::getTitleEn, r::setTitleEn, r::getDescriptionEn, r::setDescriptionEn);
        mergeTranslationField("pt", m, r::getTitlePt, r::setTitlePt, r::getDescriptionPt, r::setDescriptionPt);
        mergeTranslationField("zh", m, r::getTitleZh, r::setTitleZh, r::getDescriptionZh, r::setDescriptionZh);
        if (Texts.has(r.getTitleEs())) {
            return;
        }
        BulkProductDtoIn.BulkTranslation any = m.values().stream()
                .filter(t -> t != null && Texts.has(t.getTitle())).findFirst().orElse(null);
        if (any == null) {
            return;
        }
        r.setTitleEs(any.getTitle().trim());
        if (!Texts.has(r.getDescriptionEs())) {
            r.setDescriptionEs(any.getDescription());
        }
    }

    /**
     * UPSERT idempotente por externalId: el producto se ACTUALIZA EN SITIO (mismo id, se preservan
     * enlaces, favoritos y pedidos). El producto y sus traducciones ya los upsertan upsertProduct y el
     * writer; aquí sólo se limpian las COLECCIONES HIJAS que se reconstruyen (variantes, opciones,
     * imágenes, atributos, fichas técnicas y tramos) por product_id ANTES de recrearlas, para no
     * duplicarlas al reimportar. El producto padre NO se borra, así que su id no cambia (a diferencia
     * de un delete + create).
     */
    private void deleteRebuiltChildRows(BulkProductDtoIn r, String externalId) {
        if (!Texts.has(r.getExternalId())) {
            return;
        }
        productJpaRepository.findFirstByExternalId(externalId).ifPresent(existing -> {
            UUID exId = existing.getId();
            for (String table : PRODUCT_CHILD_TABLES) {
                // NOSONAR java:S2077 — nombre de tabla de una constante del código; valor parametrizado.
                jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", exId); // NOSONAR
            }
        });
    }

    /**
     * Imágenes del producto. Se aceptan varias claves (imageUrls/images/photos/... vía @JsonAlias) y el
     * atajo {@code imageUrl} (string suelto). Si no hay NINGUNA a nivel de producto se usan como respaldo
     * las de las variantes (o las de los valores/colores del eje), para no rechazar un producto cuya
     * única imagen vive en la variante. La primera posición es la MAIN, el resto galería.
     */
    private static List<IngestImage> ingestImagesOf(BulkProductDtoIn r, String esTitle) {
        List<IngestImage> images = new ArrayList<>();
        int position = 0;
        for (String url : BulkProductRules.imageUrlsOf(r, esTitle)) {
            images.add(new IngestImage(url, position, position == 0 ? "MAIN" : GALLERY));
            position++;
        }
        return images;
    }

    /** El writer publica como ACTIVE por defecto; sólo se degrada a DRAFT si el operador lo pidió. */
    private void applyRequestedDraftStatus(UUID id, BulkProductDtoIn r) {
        if (r.getStatus() == null || !"DRAFT".equalsIgnoreCase(r.getStatus().trim())) {
            return;
        }
        productJpaRepository.findById(id).ifPresent(p -> {
            p.setStatus(ProductStatus.DRAFT);
            productJpaRepository.save(p);
            productIndexer.indexProduct(id);
        });
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
        for (BulkProductDtoIn.BulkReview rv : reviews) {
            // Una reseña sin cuerpo NI título no dice nada al comprador: se descarta en vez de guardarla vacía.
            if (Texts.has(rv.getBody()) || Texts.has(rv.getTitle())) {
                productReviewJpaRepositoryAdapter.save(toReviewEntity(ref, rv));
            }
        }
    }

    /**
     * Reseña real de la carga. Sin autor se firma como "Anónimo" y sin idioma se asume español (es el
     * idioma por defecto del escaparate); la puntuación se acota a 1..5, que es lo que pinta la ficha.
     */
    private static ProductReviewEntity toReviewEntity(ProductEntity product, BulkProductDtoIn.BulkReview rv) {
        short rating = rv.getRating() != null ? (short) Math.clamp(rv.getRating(), 1, 5) : 5;
        return ProductReviewEntity.builder()
                .product(product)
                .authorName(Texts.has(rv.getAuthorName()) ? rv.getAuthorName().trim() : "Anónimo")
                .authorCountry(rv.getAuthorCountry())
                .rating(rating)
                .title(rv.getTitle())
                .body(rv.getBody())
                .tags(rv.getTags() != null ? String.join(",", rv.getTags()) : null)
                // Una reseña que llega en la carga del catálogo NO puede marcarse como compra verificada,
                // diga lo que diga el fichero de origen: no hay ninguna compra en esta tienda detrás de
                // ella. Afirmar lo contrario está en la lista negra de prácticas desleales de la
                // Directiva Omnibus, que se sanciona sin necesidad de probar que alguien fue engañado.
                // El distintivo se gana en ProductReviewUseCase, cuando escribe quien sí compró.
                .verifiedPurchase(false)
                .source(ReviewSource.SUPPLIER)
                .approved(true)
                .language(Texts.has(rv.getLanguage()) ? rv.getLanguage().trim().toLowerCase() : "es")
                .build();
    }

    /** Fija los campos de logística/aduana sobre la entidad gestionada (dentro de la transacción del writer). */
    private void applyLogistics(ProductEntity p, BulkProductDtoIn r) {
        // Envío e IVA (CNY): obligatorios en la carga; se suman al total SIN margen (ver PricingService).
        p.setShippingCny(r.getShippingCny());
        p.setIvaCny(r.getIvaCny());
        BulkProductFields.applyPackageDimensions(p, r);
        BulkProductFields.applyCustomsFields(p, r);
        // Lo que la carga no traiga (partida arancelaria, material, uso, batería y medidas del paquete) se
        // completa con el perfil de la categoría: sin esos datos el envío no se puede cotizar ni declarar.
        customsProfileService.applyDefaults(p, p.getCategory() != null ? p.getCategory().getSlug()
                : r.getCategorySlug());
        BulkProductFields.applyCommercialFields(p, r);
        BulkProductFields.applyRatingBreakdown(p, r);
        // supplierSkuId + peso/dimensiones por variante (DROP-675): se emparejan por SKU sobre las
        // variantes ya creadas por upsertProduct.
        BulkProductFields.applyVariantLogistics(p, r);
        BulkProductFields.applyVariantValueTranslations(p, r);
        replaceProductAttributes(p, r);
        replaceProductSpecifications(p, r);
        BulkProductFields.applyExtraTranslations(p, r);
        // Las traducciones (título + descripción por idioma) las fija el writer dentro de su transacción.
        // DROP-679: como el writer publica el producto (status ACTIVE), generamos aquí el SEO por idioma
        // a partir de esas traducciones reales (las colecciones ya están adjuntas a la entidad gestionada).
        ProductSeoMetadata.generate(p);
    }

    /**
     * Atributos taxonómicos (las facetas del buscador): se reemplazan ENTEROS en cada import. Acumularlos
     * dejaría conviviendo el valor viejo y el nuevo, y el filtro devolvería el producto por los dos.
     */
    private void replaceProductAttributes(ProductEntity p, BulkProductDtoIn r) {
        if (r.getAttributes() == null || r.getAttributes().isEmpty()) {
            return;
        }
        productAttributeRepository.deleteAll(productAttributeRepository.findByProduct_Id(p.getId()));
        for (BulkProductDtoIn.BulkAttr a : r.getAttributes()) {
            if (Texts.has(a.getKey()) && Texts.has(a.getValue())) {
                // DROP-672: locale opcional (es/en/pt/zh); en blanco = neutral (faceta).
                String locale = Texts.has(a.getLocale()) ? a.getLocale().trim().toLowerCase() : null;
                productAttributeRepository.save(ProductAttributeEntity.builder().product(p).attrKey(a.getKey())
                        .attrValue(a.getValue()).locale(locale).createdAt(Instant.now()).build());
            }
        }
    }

    /**
     * Ficha técnica por idioma: también se reemplaza entera en cada import.
     *
     * <p>La posición de respaldo avanza con TODAS las filas leídas, no sólo con las guardadas: así una
     * fila incompleta descartada no reordena las siguientes respecto al fichero original.
     */
    private void replaceProductSpecifications(ProductEntity p, BulkProductDtoIn r) {
        if (r.getSpecifications() == null || r.getSpecifications().isEmpty()) {
            return;
        }
        productSpecificationRepository
                .deleteAll(productSpecificationRepository.findByProduct_IdOrderByPositionAsc(p.getId()));
        int fallbackPosition = 0;
        for (BulkProductDtoIn.BulkSpec s : r.getSpecifications()) {
            if (Texts.has(s.getKey()) && Texts.has(s.getValue())) {
                productSpecificationRepository.save(ProductSpecificationEntity.builder().product(p)
                        .locale(Texts.firstNonBlankOr("es", s.getLocale()).trim().toLowerCase())
                        .specKey(s.getKey()).specValue(s.getValue())
                        .position(s.getPosition() != null ? s.getPosition() : fallbackPosition)
                        .createdAt(Instant.now()).build());
            }
            fallbackPosition++;
        }
    }

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public BulkResultDtoOut bulkCreateCategories(
            List<BulkCategoryDtoIn> rows) {
        int created = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                upsertBulkCategory(rows.get(i));
                created++;
            } catch (Exception e) {
                failed++;
                errors.add("Fila " + (i + 1) + ": " + ErrorMessages.humanize(e));
            }
        }
        return new BulkResultDtoOut(created, failed, errors);
    }

    /**
     * Alta o actualización de UNA categoría de la carga. Los nombres que falten caen al español, el único
     * obligatorio. El padre se resuelve por slug: uno listado antes en el mismo lote ya está persistido y
     * se ve desde aquí; un slug desconocido tumba SÓLO esta fila (lo recoge el catch del bucle).
     */
    private void upsertBulkCategory(BulkCategoryDtoIn r) {
        if (!Texts.has(r.getNameEs())) {
            throw new BusinessException("nameEs es obligatorio");
        }
        int position = r.getPosition() != null ? r.getPosition() : (int) (categoryRepository.count() + 1);
        String pt = Texts.firstNonBlankOr(r.getNameEs(), r.getNamePt());
        String en = Texts.firstNonBlankOr(r.getNameEs(), r.getNameEn());
        String zh = Texts.firstNonBlankOr(r.getNameEs(), r.getNameZh());
        UUID parentId = null;
        if (Texts.has(r.getParentSlug())) {
            parentId = categoryRepository.findBySlug(r.getParentSlug()).map(CategoryEntity::getId)
                    .orElseThrow(() -> new BusinessException("Categoría padre no encontrada: " + r.getParentSlug()));
        }
        self.upsertCategory(new IngestCategoryRequest(r.getSlug(), parentId, "1688", null, zh, position,
                r.getIcon() != null ? r.getIcon() : "tag",
                Map.of("es", r.getNameEs(), "en", en, "pt", pt)));
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
            Optional<Category1688MappingEntity> m = category1688MappingRepository.findByExternal1688Id(r.getCategory1688Id().trim());
            if (m.isPresent()) {
                return m.get().getCategory();
            }
        }
        if (r.getCategory1688Name() != null && !r.getCategory1688Name().isBlank()) {
            Optional<Category1688MappingEntity> m = category1688MappingRepository.findFirstByExternal1688NameIgnoreCase(r.getCategory1688Name().trim());
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
                .orElseThrow(() -> new NotFoundException(CATEGORY_NOT_FOUND + categoryId));
        Optional<Category1688MappingEntity> existing = category1688MappingRepository.findByExternal1688Id(external1688Id.trim());
        Category1688MappingEntity m = existing.orElseGet(
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
                .orElseThrow(() -> new NotFoundException(CATEGORY_NOT_FOUND + categoryId));
        Optional<CategoryAttributeSchemaEntity> existing = categoryAttributeSchemaRepository.findByCategory_IdAndAttrKey(categoryId, attrKey.trim());
        CategoryAttributeSchemaEntity s = existing.orElseGet(
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
            Optional<SupplierEntity> existing = supplierRepository.findBySourceAndExternalId("1688", ext);
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
