package com.nexaplatform.dropshipping.application.usecase.impl;

import com.github.slugify.Slugify;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.AnuncioBusFallidoView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CustomsAuditView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductCustomsGapView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTranslation;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.BulkProductFields;
import com.nexaplatform.dropshipping.application.service.BulkProductRules;
import com.nexaplatform.dropshipping.application.service.BulkProductStructure;
import com.nexaplatform.dropshipping.application.service.CatalogReindexRunner;
import com.nexaplatform.dropshipping.application.service.CustomsDataCheck;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.service.ProductSeoMetadata;
import com.nexaplatform.dropshipping.application.service.SupplierSourceUrl;
import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.BusAnuncioEstado;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.VideoMirrorService;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.Category1688MappingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.Category1688MappingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryAttributeSchemaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import com.nexaplatform.dropshipping.infrastructure.seed.CatalogFillWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
 * {@link ProductMapper}) and the admin mutations (status, quick-edit,
 * duplicate).
 * Mutations go through the {@link ProductRepository} domain port operating on
 * the
 * {@link Product} model; the legacy collaborators are kept for the ingest
 * upsert
 * flow (managed supplier/category, separate price-tier table, Kafka events) and
 * the live-pricing read projections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogUseCaseImpl implements CatalogUseCase {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por
    // valor.
    private static final String CATEGORY_NOT_FOUND = "Category not found: ";
    private static final String PRODUCT_NOT_FOUND = "Product not found: ";
    private static final String PRODUCT_NOT_FOUND_2 = "Product not found";
    private static final String VARIANT_NOT_FOUND = "Variant not found";
    private static final String VARIANT_VALUE = "Variant value";
    private static final String GALLERY = "GALLERY";
    /** Fotos de la DESCRIPCIÓN: van aparte de la galería y el escaparate las pinta en su propia sección. */
    private static final String DETAIL = "DETAIL";

    private static final Slugify SLUG = Slugify.builder().lowerCase(true).build();

    /**
     * Cuántos productos se traen por tanda al auditar la aduana. Ni una consulta
     * por producto, ni todos.
     */
    private static final int AUDIT_BATCH_SIZE = 300;

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CategoryRepository categoryRepository;
    /**
     * Completa HS code, material, uso, batería y medidas de paquete desde el perfil
     * de la categoría.
     */
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
    /**
     * El bus de integración está apagado por defecto y solo se enciende en
     * preproducción, así que
     * el bean puede no existir. ObjectProvider lo tolera sin condicionar el
     * arranque del catálogo.
     */
    private final ObjectProvider<CatalogoBusService> busCatalogo;
    private final CategoryIndexer categoryIndexer;
    private final ProductAttributeRepository productAttributeRepository;
    private final ProductSpecificationRepository productSpecificationRepository;
    private final VariantValueRepository variantValueRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ProductBulkExportMapper bulkExportMapper;
    private final ImageMirrorService imageMirrorService;

    private final VideoMirrorService videoMirrorService;
    /**
     * Ejecutor del reindexado completo en segundo plano (evita el timeout del
     * proxy/edge).
     */
    private final CatalogReindexRunner reindexRunner;
    /**
     * DROP-677: mapeo de categorías de 1688 → categoría interna, usado al resolver
     * la fila de carga.
     */
    private final Category1688MappingRepository category1688MappingRepository;
    /**
     * DROP-670: esquema de atributos obligatorios por categoría, validado en cada
     * alta masiva.
     */
    private final CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;
    /** Reseñas reales que vienen en la carga masiva. */
    private final ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

    @PersistenceContext
    private EntityManager em;

    // Auto-referencia POR EL PROXY. Los métodos de lote (runBatch,
    // bulkCreateCategories) documentan que
    // cada elemento va en su propia transacción, pero llamarlos con this los
    // saltaba: la autoinvocación no
    // pasa por el proxy de Spring, así que el @Transactional del método invocado no
    // se aplicaba y el lote
    // entero corría sin transacción propia. @Lazy evita el ciclo de construcción
    // consigo mismo.
    private CatalogUseCase self;

    // El escritor ingesta a través de ESTE mismo caso de uso, así que por
    // constructor el ciclo no se
    // podría resolver; @Lazy difiere la resolución hasta el primer uso.
    private CatalogFillWriter catalogFillWriter;

    // Inyección por método (no por campo): ni la auto-referencia ni el escritor
    // pueden entrar por el
    // constructor sin cerrar un ciclo de creación, y el setter deja la dependencia
    // explícita en la API
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

    // Las cachés de categorías se invalidan también aquí: el alta delegaba en
    // upsertCategory() con this y
    // la autoinvocación no pasa por el proxy, así que sus @CacheEvict no llegaban a
    // ejecutarse y la
    // categoría recién creada no salía en el escaparate hasta que caducaba la
    // caché.
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

    /**
     * Alta/actualización real de la categoría; las anotaciones viven en los métodos
     * públicos de entrada.
     */
    private CategoryEntity upsertCategoryInternal(IngestCategoryRequest req) {
        CategoryEntity entity = categoryRepository.findBySlug(req.slug())
                .orElseGet(() -> CategoryEntity.builder().slug(req.slug()).active(true).build());
        entity.setNameZh(req.nameZh());
        entity.setSource(req.source() != null ? req.source() : "1688");
        entity.setExternalId(req.externalId());
        entity.setPosition(req.position());
        entity.setIcon(req.icon());
        if (req.parentId() != null) {
            // Con orElse(null) un padre inexistente NO se ignoraba: borraba el padre que la
            // categoría ya
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
            // Re-save so the cascade actually persists the translation rows (they are added
            // to the
            // collection after the first save; without this they were silently dropped).
            entity = categoryRepository.save(entity);
        }
        categoryIndexer.indexCategory(entity.getId());
        CatalogoBusService bus = busCatalogo.getIfAvailable();
        if (bus != null) {
            bus.publicarCategoria(entity);
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

        // Las colecciones hijas se REEMPLAZAN enteras en cada ingesta: es lo que hace
        // que reimportar sea
        // idempotente en vez de ir acumulando imágenes y variantes duplicadas.
        replaceImages(product, req);
        // DROP variant-images: una variante o un valor sin imagen propia cae a la
        // principal del producto,
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
        // Lo YA espejado, por dirección de origen. Una imagen cuya dirección no cambia ES la misma
        // imagen, y volver a espejarla no aporta nada: cuesta descargarla y subirla otra vez, y
        // mientras tanto el producto DESAPARECE del escaparate, que solo enseña lo que tiene foto en
        // nuestro CDN.
        //
        // Medido el 14-sep-2026: al resubir unos cientos de productos para completarles la traducción,
        // las imágenes pendientes de espejar pasaron de 6.790 a 14.167 y 550 productos se quedaron
        // invisibles. Sobre el repaso del catálogo entero habrían sido ~200.000 imágenes re-espejadas
        // para nada, con medio escaparate vacío durante días.
        Map<String, ProductImageEntity> espejadas = new HashMap<>();
        for (ProductImageEntity vieja : product.getImages()) {
            if (vieja.getSourceUrl() != null && vieja.getCdnUrl() != null) {
                espejadas.put(vieja.getSourceUrl(), vieja);
            }
        }
        product.getImages().clear();
        if (req.images() == null) {
            return;
        }
        for (IngestImage img : req.images()) {
            // Los iconos e insignias de la interfaz del proveedor llegan mezclados con las fotos y se
            // espejan igual de bien —existen—, así que nada los delata después: acaban en la galería
            // como una imagen más. Se descartan aquí, en la puerta. La posición de las demás no se
            // recalcula: un hueco en la numeración no cambia el orden, y el orden viene de 1688.
            if (!BulkProductRules.isProductPhoto(img.sourceUrl())) {
                log.info("Imagen descartada por ser un recurso de la interfaz del proveedor: {}", img.sourceUrl());
                continue;
            }
            ProductImageEntity antes = espejadas.get(img.sourceUrl());
            product.getImages().add(ProductImageEntity.builder().product(product).position(img.position())
                    .role(img.role() != null ? img.role() : GALLERY).sourceUrl(img.sourceUrl())
                    // Se hereda lo que costó traer: la copia en el CDN, sus medidas y su huella. Lo
                    // que NO se hereda es la posición ni el papel: esos vienen de la ficha nueva.
                    .cdnUrl(antes != null ? antes.getCdnUrl() : null).width(antes != null ? antes.getWidth() : null)
                    .height(antes != null ? antes.getHeight() : null).bytes(antes != null ? antes.getBytes() : null)
                    .hash(antes != null ? antes.getHash() : null)
                    .mirroredAt(antes != null ? antes.getMirroredAt() : null)
                    .mirrorStatus(antes != null ? MirrorStatus.MIRRORED : MirrorStatus.PENDING).build());
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
                    opt.getValues()
                            .add(VariantValueEntity.builder().option(opt).valueZh(v.valueZh()).position(v.position())
                                    .imageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl)
                                    .build());
                }
            }
            product.getVariantOptions().add(opt);
        }
    }

    /**
     * Variantes comprables. UPSERT por externalId: las variantes que ya existen se
     * ACTUALIZAN (mismo
     * registro, sin borrar) y solo se crean las nuevas. Las que ya no vienen en el
     * feed se DESACTIVAN
     * (active=false) en vez de borrarse.
     *
     * <p>
     * Antes se hacía clear() + re-crear: con orphanRemoval=true JPA borraba TODAS
     * las variantes y las
     * recreaba, y si una variante vieja estaba referenciada por un pedido
     * (order_item) o un snapshot de
     * inventario, el DELETE saltaba la FK y la reimportación de un producto ya
     * cargado fallaba con
     * "registro que no existe (categoría, proveedor o relación inválida)". El bulk
     * es un UPSERT por JSON:
     * reintentar debe actualizar el producto, no romperse.
     */
    private void replaceVariants(ProductEntity product, IngestProductRequest req, String mainImageUrl) {
        Map<String, ProductVariantEntity> existentes = new LinkedHashMap<>();
        for (ProductVariantEntity v : product.getVariants()) {
            if (v.getExternalId() != null) {
                existentes.put(v.getExternalId(), v);
            }
        }
        List<ProductVariantEntity> resultado = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        if (req.variants() != null) {
            for (IngestVariant v : req.variants()) {
                ProductVariantEntity ent;
                if (v.externalId() != null && existentes.containsKey(v.externalId())) {
                    ent = existentes.get(v.externalId());
                    vistos.add(v.externalId());
                } else {
                    ent = ProductVariantEntity.builder().product(product).externalId(v.externalId()).build();
                }
                ent.setSku(v.sku());
                ent.setTitle(v.title());
                ent.setPrice(v.price());
                ent.setStock(v.stock() != null ? v.stock() : 0);
                ent.setImageSourceUrl(v.imageSourceUrl() != null ? v.imageSourceUrl() : mainImageUrl);
                ent.setOptions(v.options());
                ent.setActive(true);
                resultado.add(ent);
            }
        }
        // Las variantes que ya no vienen en el feed se desactivan (no se borran: un
        // pedido histórico
        // puede seguir referenciándolas y la FK order_item_variant_id_fkey no debe
        // romperse).
        for (ProductVariantEntity v : product.getVariants()) {
            if (v.getExternalId() != null && !vistos.contains(v.getExternalId())) {
                v.setActive(false);
                resultado.add(v);
            }
        }
        product.getVariants().clear();
        product.getVariants().addAll(resultado);
    }

    /**
     * Tramos de precio: van en su propia tabla, así que se borran y recrean aparte
     * del producto.
     */
    private void replacePriceTiers(ProductEntity product, IngestProductRequest req) {
        if (req.priceTiers() == null) {
            return;
        }
        List<ProductPriceTierEntity> previos = priceTierRepository.findByProductIdOrderByMinQtyAsc(product.getId());
        // Los recargos que YA tenían, por tramo, antes de borrarlos.
        //
        // Se guardan porque esto borra y recrea: el scraper no manda recargo de tramo —y hace
        // bien, lo fija el panel— así que el tramo renacía sin él y el trabajo del administrador
        // duraba hasta la siguiente extracción, sin ningún error.
        Map<Integer, BigDecimal> recargosPrevios = new HashMap<>();
        for (ProductPriceTierEntity previo : previos) {
            if (previo.getSurchargeCny() != null) {
                recargosPrevios.put(previo.getMinQty(), previo.getSurchargeCny());
            }
        }
        previos.forEach(priceTierRepository::delete);
        for (IngestPriceTier t : req.priceTiers()) {
            priceTierRepository.save(ProductPriceTierEntity.builder().product(product).minQty(t.minQty())
                    .maxQty(t.maxQty()).unitPrice(t.unitPrice())
                    .surchargeCny(BulkProductFields.tierSurcharge(recargosPrevios, t.minQty(), t.surchargeCny()))
                    .currency(t.currency() != null ? t.currency() : "CNY").build());
        }
    }

    /**
     * Avisa al indexador de que el producto cambió. El id puede faltar todavía —JPA
     * lo asigna al volcar, y
     * en un test con dobles puros puede no llegar nunca—, y en ese caso no se
     * publica en vez de reventar.
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
            int size, String language, String sort, Boolean verified, BigDecimal minCost, BigDecimal maxCost,
            Integer minSales, BigDecimal minTrend) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200), adminSort(sort));
        ProductStatus st = parseStatusTolerant(status);
        // Free-text search runs server-side across the WHOLE catalogue and ALL
        // languages (same rules as the
        // storefront), so the admin box finds products in any page and in any language
        // — not just a client-side
        // substring over the current page.
        // needle "" (no nulo) evita el error de tipo de Postgres al bindear null en el
        // LIKE; la query usa
        // (:needle = '' OR ...). searchAdmin también sirve como ruta del filtro
        // `verified` (con o sin texto/categoría).
        String needle = (query == null || query.isBlank()) ? "" : Texts.escapeLikeWildcards(query.trim().toLowerCase());
        if (!needle.isEmpty() || verified != null) {
            // El fuzzy se acota al idioma que está viendo el admin (contra los 8 a la vez,
            // "botas" casaba
            // con el "botao" portugués). Las descripciones largas solo se rastrean si el
            // match por
            // título/atributo/variante no ha encontrado NADA — así el ruido no tapa lo
            // relevante, pero el
            // admin sigue pudiendo localizar un producto por una frase que solo está en su
            // descripción.
            String lang = (language == null || language.isBlank()) ? "es" : language.toLowerCase();
            Page<ProductEntity> found = productJpaRepository.searchAdmin(st, categoryId, needle, verified, lang, false,
                    minCost, maxCost, minSales, minTrend, null, null, pageable);
            if (!needle.isEmpty() && found.getTotalElements() == 0) {
                found = productJpaRepository.searchAdmin(st, categoryId, needle, verified, lang, true, minCost, maxCost,
                        minSales, minTrend, null, null, pageable);
            }
            return found.map(p -> productMapper.toSummary(p, language));
        }
        /*
         * Sin texto ni filtro de verificado se usaba una consulta más simple. Ahora TAMBIÉN se pasa por
         * `searchAdmin` cuando hay filtros de tabla: son parte del WHERE, y resolverlos por otro camino
         * dejaría el total sin cuadrar con las filas —el síntoma clásico de un listado que dice «7729»
         * mientras enseña 61—.
         */
        if (minCost != null || maxCost != null || minSales != null || minTrend != null) {
            String lang = (language == null || language.isBlank()) ? "es" : language.toLowerCase();
            return productJpaRepository.searchAdmin(st, categoryId, "", null, lang, false, minCost, maxCost, minSales,
                    minTrend, null, null, pageable).map(p -> productMapper.toSummary(p, language));
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
     * Orden para el listado de admin. {@code price_*} ordena por el precio CNY
     * persistido
     * ({@code basePrice}) — el margen es multiplicativo, así que el orden coincide
     * con el de venta.
     */
    private static Sort adminSort(String sort) {
        // NUNCA se devuelve Sort.unsorted(): paginar sin ORDER BY deja el orden a
        // criterio de PostgreSQL,
        // que con LIMIT/OFFSET no garantiza ser el mismo entre dos consultas. El
        // resultado es que la misma
        // fila puede salir en dos páginas y otra no salir en ninguna — el listado del
        // admin se saltaría
        // productos sin avisar. Ordenar por id es barato (clave primaria) y estable.
        Sort criterio = switch (sort == null ? "" : sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "basePrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "newest" -> Sort.by(Sort.Direction.DESC, "ingestedAt");
            case "oldest" -> Sort.by(Sort.Direction.ASC, "ingestedAt");
            default -> Sort.unsorted();
        };
        // Y el desempate va SIEMPRE, también sobre los criterios explícitos:
        // `ingestedAt` y `basePrice`
        // empatan de sobra en un catálogo cargado por lotes.
        return criterio.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language) {
        return pageProducts(status, pageable, language);
    }

    /**
     * Página de productos sin anotación transaccional, para que la reutilice el
     * listado de admin de esta
     * misma clase: la autoinvocación no pasa por el proxy y el
     * {@code @Transactional} del método invocado
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
        return productRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + slug));
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
        // Un producto retirado no existe para el escaparate. El listado ya lo esconde
        // (exige ACTIVE) y el
        // cobro ya lo rechaza con PRODUCT_UNAVAILABLE, pero la ficha por slug —a la que
        // se llega por enlace
        // directo, por un resultado indexado o desde un correo antiguo— seguía
        // sirviéndose entera, con su
        // precio: enseñaba un escaparate de algo que no se puede comprar. Para el admin
        // sí tiene que abrirse,
        // porque desde el panel se revisa y se reactiva justo lo que está pausado o
        // archivado.
        if (!SecurityUtils.isAdmin() && p.getStatus() != ProductStatus.ACTIVE) {
            throw new NotFoundException(PRODUCT_NOT_FOUND + slug);
        }
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
     * Ficha por id sin anotaciones: la reutiliza la búsqueda por identificador
     * externo de esta misma
     * clase. La autoinvocación no pasa por el proxy, así que ni el
     * {@code @Cacheable} ni el
     * {@code @Transactional} del método invocado se aplicaban; ahora viven solo en
     * el método de entrada.
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

    /**
     * Trigger lazy collections while still inside the transaction
     * (open-in-view=false).
     */
    private void forceLoadCollections(ProductEntity p) {
        p.getImages().size();
        p.getVariants().size();
        p.getVariantOptions().forEach(o -> o.getValues().forEach(v -> v.getTranslations().size()));
        p.getTranslations().size();
    }

    // Esta variante también invalida las cachés de producto: delegaba en
    // updateStatus(UUID, ProductStatus)
    // con this y la autoinvocación no pasa por el proxy, así que sus @CacheEvict no
    // se ejecutaban y el
    // escaparate seguía sirviendo el producto con el estado anterior hasta que la
    // entrada caducaba.
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true)})
    public void updateStatus(UUID id, String status) {
        // valueOf crudo daba un 500 con un estado desconocido, mientras el listado del
        // mismo panel tolera
        // basura. Aquí sí hay que rechazarlo —cambiar el estado a «lo que sea» no
        // significa nada— pero como
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

    /**
     * Cambio de estado real; las anotaciones de caché y transacción viven en los
     * métodos de entrada.
     */
    private void applyStatus(UUID id, ProductStatus status) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        requireCustomsDataToPublish(p, status);
        p.setStatus(status);
        // DROP-679: al publicar se generan los metadatos SEO por idioma a partir del
        // contenido real
        // (título/descripción ya traducidos), sin sobrescribir los que el operador haya
        // definido.
        if (status == ProductStatus.ACTIVE) {
            ProductSeoMetadata.generate(p);
        }
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
    }

    /**
     * Un producto no entra a la venta sin los datos que la aduana exige para poder
     * declararlo.
     *
     * <p>
     * Este es el sitio barato de descubrirlo. Si el producto llega al escaparate
     * incompleto, el fallo
     * no aparece hasta que alguien lo compra y hay que despachar su pedido, y
     * entonces ya hay dinero
     * cobrado: o el transportista rechaza la guía, o la aduana retiene el paquete.
     * Publicar es una acción
     * deliberada del administrador, así que es el momento natural para exigírselos.
     *
     * <p>
     * Solo se comprueba al ACTIVAR: retirar del escaparate un producto defectuoso
     * —pausarlo o
     * archivarlo— es precisamente lo que hay que poder hacer siempre, faltándole lo
     * que le falte.
     */
    private void requireCustomsDataToPublish(ProductEntity p, ProductStatus status) {
        if (status != ProductStatus.ACTIVE) {
            return;
        }
        List<CustomsDataCheck.CustomsField> faltantes = CustomsDataCheck.faltantesDe(p);
        if (faltantes.isEmpty()) {
            return;
        }
        String nombre = p.getSlug() != null && !p.getSlug().isBlank() ? p.getSlug() : p.getExternalId();
        throw new BusinessException("INCOMPLETE_CUSTOMS_DATA",
                "No se puede poner a la venta sin los datos obligatorios de aduana. "
                        + CustomsDataCheck.describe(String.valueOf(nombre), faltantes)
                        + ". Complétalos y vuelve a publicarlo.");
    }

    /**
     * Repasa el catálogo y devuelve qué productos no se podrían declarar en aduana
     * y qué les falta.
     *
     * <p>
     * Existe porque con miles de referencias abrirlas una a una para ver cuál está
     * coja no es viable:
     * el administrador necesita la lista de golpe para arreglarlas en bloque. Se
     * lee en tandas —ids
     * primero, luego los productos con sus traducciones y variantes— para no
     * traerse el catálogo entero
     * a memoria, y se corta en {@code max} filas avisando de que quedan más.
     *
     * <p>
     * El veredicto lo da {@link CustomsDataCheck}, el mismo que bloquea la
     * publicación y el despacho:
     * lo que aquí sale como incompleto es exactamente lo que allí no va a pasar.
     */
    @Override
    @Transactional(readOnly = true)
    public CustomsAuditView auditCustomsData(String status, int max) {
        ProductStatus filtro = parseStatusTolerant(status);
        int tope = Math.max(1, max);
        List<ProductCustomsGapView> incompletos = new ArrayList<>();
        long revisados = 0;
        long totalIncompletos = 0;
        boolean quedanMas = false;
        int pagina = 0;
        boolean hayMasPaginas = true;
        while (hayMasPaginas) {
            Page<UUID> ids = productJpaRepository.findIdsForCustomsAudit(filtro,
                    // Ordenado por id: la paginación tiene que ser estable mientras se recorre el
                    // catálogo, o un producto se repetiría en dos tandas y otro no saldría en
                    // ninguna.
                    PageRequest.of(pagina, AUDIT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            List<UUID> lote = ids.getContent();
            if (!lote.isEmpty()) {
                // Las traducciones y las variantes van en dos consultas porque no se pueden
                // traer las dos
                // colecciones en el mismo JOIN FETCH; la segunda rellena las mismas instancias.
                List<ProductEntity> productos = productJpaRepository.findWithTranslationsByIds(lote);
                productJpaRepository.findWithVariantsByIds(lote);
                for (ProductEntity p : productos) {
                    revisados++;
                    List<CustomsDataCheck.CustomsField> faltantes = CustomsDataCheck.faltantesDe(p);
                    if (faltantes.isEmpty()) {
                        continue;
                    }
                    totalIncompletos++;
                    if (incompletos.size() < tope) {
                        incompletos.add(toCustomsGapView(p, faltantes));
                    } else {
                        quedanMas = true;
                    }
                }
            }
            hayMasPaginas = ids.hasNext();
            pagina++;
        }
        return new CustomsAuditView(revisados, totalIncompletos, quedanMas, incompletos);
    }

    /**
     * Una fila de la auditoría: lo justo para reconocer el producto y saber qué
     * corregirle.
     */
    private static ProductCustomsGapView toCustomsGapView(ProductEntity p,
            List<CustomsDataCheck.CustomsField> faltantes) {
        List<String> etiquetas = new ArrayList<>();
        for (CustomsDataCheck.CustomsField campo : faltantes) {
            etiquetas.add(campo.etiqueta());
        }
        return new ProductCustomsGapView(p.getId(), p.getSlug(), p.getTitleZh(), p.getExternalId(),
                p.getStatus() != null ? p.getStatus().name() : null, etiquetas);
    }

    /**
     * DROP-679: rellena meta_title/meta_description (solo si están vacíos) desde el
     * contenido real.
     */

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
    public ProductDetailView updatePriceTierSurcharge(UUID productId, int minQty, BigDecimal surchargeCny,
            String lang) {
        ProductEntity p = productJpaRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + productId));
        ProductPriceTierEntity tramo = priceTierRepository.findByProductIdOrderByMinQtyAsc(productId).stream()
                .filter(t -> t.getMinQty() == minQty).findFirst().orElseThrow(() -> new NotFoundException(
                        "Price tier not found: product " + productId + ", minQty " + minQty));
        tramo.setSurchargeCny(surchargeCny);
        priceTierRepository.save(tramo);
        // Se vuelve a leer la lista porque la que hay en memoria es la de ANTES de guardar, y lo que se
        // devuelve es justamente el precio recalculado del tramo que se acaba de tocar.
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(productId));
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        boolean estabaCertificado = Boolean.TRUE.equals(p.getVerified());
        applyQuickEditScalars(p, req);
        applyQuickEditVideo(p, req);
        applyQuickEditTranslation(p, req, lang);
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
        anunciarAlBus(p, estabaCertificado);
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    /**
     * Update en lote del recargo fijo por producto (surcharge_cny, 30-ago-2026).
     *
     * <p>El admin lo puede fijar por producto ({@code productIds}), por categoría ({@code categoryId}) o
     * para todo el catálogo (ambos vacíos). Se hace con UN update SQL en lugar de cargar y guardar cada
     * entidad: el catálogo tiene miles de productos y un update masivo por JPA tardaría minutos.
     *
     * <p>Se evictan las cachés de catálogo (detalle/summary/list) porque el recargo entra en el precio de
     * venta. El índice de OpenSearch NO se toca: {@code surcharge_cny} no viaja al índice (no se busca por
     * él) y el precio de escaparate se calcula en vivo contra la BD, no contra el índice.
     *
     * @return número de productos actualizados
     */
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public int bulkUpdateSurcharge(List<UUID> productIds, UUID categoryId, BigDecimal surchargeCny) {
        BigDecimal valor = surchargeCny != null ? surchargeCny : BigDecimal.ZERO;
        int actualizados;
        if (productIds != null && !productIds.isEmpty()) {
            // Recargo solo para los productos indicados.
            String in = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
            actualizados = jdbcTemplate.update(
                    "UPDATE product SET surcharge_cny = ?, updated_at = now() WHERE id IN (" + in + ")",
                    parametrosConValor(valor, productIds));
        } else if (categoryId != null) {
            // Recargo para toda una categoría.
            actualizados = jdbcTemplate.update(
                    "UPDATE product SET surcharge_cny = ?, updated_at = now() WHERE category_id = ?", valor,
                    categoryId);
        } else {
            // Sin filtro: todo el catálogo (update masivo global).
            actualizados = jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now()", valor);
        }
        // El recargo es un componente del precio: los productos certificados afectados se re-anuncian al
        // bus para que el cambio llegue al destino (el export del bus lleva surcharge_cny).
        marcarCertificadosParaElBus(productIds, categoryId);
        return actualizados;
    }

    /**
     * Update en lote de las dos bolsas de subvención por producto.
     *
     * <p>Un importe nulo no se toca: la parte SET se arma solo con los que vienen, para poder cambiar la
     * bolsa del porte sin pisar la del arancel. Si no viene ninguno no hay nada que hacer y se devuelve
     * 0 en vez de lanzar un UPDATE sin asignaciones, que sería SQL inválido.
     */
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public int bulkUpdateSubsidy(List<UUID> productIds, UUID categoryId, BigDecimal shippingUserCny,
            BigDecimal dutyUserCny) {
        List<String> asignaciones = new ArrayList<>();
        List<Object> valores = new ArrayList<>();
        if (shippingUserCny != null) {
            asignaciones.add("shipping_user_cny = ?");
            valores.add(shippingUserCny);
        }
        if (dutyUserCny != null) {
            asignaciones.add("duty_user_cny = ?");
            valores.add(dutyUserCny);
        }
        if (asignaciones.isEmpty()) {
            return 0;
        }
        String sets = String.join(", ", asignaciones);
        int actualizados;
        if (productIds != null && !productIds.isEmpty()) {
            String in = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
            List<Object> args = new ArrayList<>(valores);
            args.addAll(productIds);
            actualizados = jdbcTemplate.update(
                    "UPDATE product SET " + sets + ", updated_at = now() WHERE id IN (" + in + ")", args.toArray());
        } else if (categoryId != null) {
            List<Object> args = new ArrayList<>(valores);
            args.add(categoryId);
            actualizados = jdbcTemplate.update(
                    "UPDATE product SET " + sets + ", updated_at = now() WHERE category_id = ?", args.toArray());
        } else {
            actualizados = jdbcTemplate.update("UPDATE product SET " + sets + ", updated_at = now()",
                    valores.toArray());
        }
        // Las bolsas son componentes de lo que paga el cliente: los productos certificados afectados se
        // re-anuncian al bus para que el cambio llegue al destino (el export del bus las lleva).
        marcarCertificadosParaElBus(productIds, categoryId);
        return actualizados;
    }

    /**
     * Deja marcados para anunciar al bus los productos CERTIFICADOS a los que afecta un update masivo.
     *
     * <p>En UNA sentencia, no producto a producto. Antes se traían los ids y se cargaba cada entidad
     * para marcarla: aplicar un recargo a todo el catálogo eran miles de consultas dentro de la
     * petición, y el administrador se quedaba mirando la pantalla hasta tener que recargarla. El
     * barrido construye después las fichas, que es donde de verdad cuesta el trabajo.
     */
    private void marcarCertificadosParaElBus(List<UUID> productIds, UUID categoryId) {
        if (busCatalogo.getIfAvailable() == null) {
            return; // entorno sin bus: no hay a quién contárselo, y la cola no debe llenarse
        }
        String marca = "UPDATE product SET bus_estado = 'PENDIENTE', bus_intentos = 0, bus_error = null "
                + "WHERE verified = true";
        if (productIds != null && !productIds.isEmpty()) {
            String in = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
            jdbcTemplate.update(marca + " AND id IN (" + in + ")", productIds.toArray());
            return;
        }
        if (categoryId != null) {
            jdbcTemplate.update(marca + " AND category_id = ?", categoryId);
            return;
        }
        jdbcTemplate.update(marca);
    }

    /** Concatena el valor del recargo delante de los ids para el UPDATE ... IN (?). */
    private Object[] parametrosConValor(BigDecimal valor, List<UUID> ids) {
        Object[] parametros = new Object[ids.size() + 1];
        parametros[0] = valor;
        for (int i = 0; i < ids.size(); i++) {
            parametros[i + 1] = ids.get(i);
        }
        return parametros;
    }

    /**
     * Cuenta al bus lo que ha pasado con la certificación del producto.
     *
     * <p>
     * Un producto que SIGUE certificado también se anuncia: si se le corrige el
     * peso o una
     * traducción, ese cambio tiene que llegar al destino. El evento es un «así
     * queda ahora», de
     * modo que reenviarlo no hace daño.
     */
    private void anunciarAlBus(ProductEntity p, boolean estabaCertificado) {
        if (busCatalogo.getIfAvailable() == null) {
            return;
        }
        if (!Boolean.TRUE.equals(p.getVerified()) && !estabaCertificado) {
            return; // ni está certificado ni lo estaba: no hay nada que contar
        }
        // Solo se deja la MARCA. Antes se construía aquí la ficha entera —cuatro consultas más el
        // mapeo de los ocho idiomas, las variantes, las imágenes y las reseñas, serializado a JSON—
        // dentro de la transacción de la petición, y por eso marcar un producto como verificado
        // tardaba segundos y aplicar un recargo a un lote tardaba eso multiplicado por N.
        //
        // La marca va en la MISMA transacción que el cambio a propósito: es lo que garantiza que un
        // producto certificado no se quede sin anunciar. Publicar después del commit habría sido más
        // sencillo, pero deja una ventana en la que el proceso se cae y el producto no llega nunca a
        // producción sin que nadie se entere. Quien construye y publica es AnuncioBusScheduler.
        p.marcarParaAnunciarAlBus();
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public ProductDetailView updateSourceUrl(UUID id, String sourceUrl, String lang) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        p.setSourceUrl(SupplierSourceUrl.requireValid(sourceUrl));
        productJpaRepository.save(p);
        productIndexer.indexProduct(id);
        return productMapper.toDetail(p, lang, priceTierRepository.findByProductIdOrderByMinQtyAsc(p.getId()));
    }

    /**
     * Campos sueltos de la ficha: sólo se toca lo que el admin manda, un null es
     * "no lo edito".
     */
    private void applyQuickEditScalars(ProductEntity p, AdminProductQuickEditDtoIn req) {
        if (req.getBrand() != null) {
            p.setBrand(req.getBrand());
        }
        // Fabricante (art. 19.a del Reglamento (UE) 2023/988). Cadena vacía sirve para
        // BORRAR el dato, igual
        // que en el resto de campos de texto de la ficha; null es "no lo edito".
        if (req.getManufacturerName() != null) {
            p.setManufacturerName(Texts.trimToNull(req.getManufacturerName()));
        }
        if (req.getManufacturerAddress() != null) {
            p.setManufacturerAddress(Texts.trimToNull(req.getManufacturerAddress()));
        }
        if (req.getManufacturerEmail() != null) {
            p.setManufacturerEmail(Texts.trimToNull(req.getManufacturerEmail()));
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
        // Envío e IVA se cargan al importar, pero hasta ahora no había forma de
        // corregirlos sin
        // reimportar el producto entero: el PUT aceptaba el campo y lo descartaba en
        // silencio.
        if (req.getShippingCny() != null) {
            p.setShippingCny(req.getShippingCny());
        }
        if (req.getMargenInternoPct() != null) {
            p.setMargenInternoPct(req.getMargenInternoPct());
        }
        // Recargo fijo por producto (30-ago-2026): se edita por producto desde la ficha; el update
        // masivo (por categoría / todo el catálogo) va por su propio endpoint.
        if (req.getSurchargeCny() != null) {
            p.setSurchargeCny(req.getSurchargeCny());
        }
        if (req.getShippingUserCny() != null) {
            p.setShippingUserCny(req.getShippingUserCny());
        }
        if (req.getDutyUserCny() != null) {
            p.setDutyUserCny(req.getDutyUserCny());
        }
        // Verificación manual del admin (checkbox del listado): true = revisado OK,
        // false = pendiente/reimportar.
        if (req.getVerified() != null) {
            p.setVerified(req.getVerified());
        }
        // Reasignar categoría desde el admin (selector de la ficha). Se resuelve por id
        // y se valida que exista.
        if (req.getCategoryId() != null) {
            p.setCategory(categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new NotFoundException(CATEGORY_NOT_FOUND + req.getCategoryId())));
        }
    }

    /**
     * DROP-673: edición del vídeo real del producto desde el admin (cadena vacía lo
     * elimina).
     */
    private void applyQuickEditVideo(ProductEntity p, AdminProductQuickEditDtoIn req) {
        if (req.getVideoUrl() == null) {
            return;
        }
        String url = req.getVideoUrl().trim();
        // cambiarVideoUrl y no setVideoUrl: pone el vídeo nuevo en cola para espejarlo y tira lo espejado
        // del anterior, que ya no corresponde a este producto.
        p.cambiarVideoUrl(url.isEmpty() ? null : url);
        // Borrar el vídeo principal no significa que el producto se quede sin vídeo:
        // puede quedar alguno
        // en la lista secundaria, y en ese caso la ficha lo sigue anunciando.
        p.setHasVideo(!url.isEmpty() || (p.getVideoUrls() != null && !p.getVideoUrls().isEmpty()));
    }

    /**
     * Contenido del idioma activo (título, descripciones y SEO). Se edita la
     * traducción, NUNCA el
     * {@code title_zh} canónico: ese es el dato del proveedor y es el que empareja
     * las reimportaciones.
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

    /**
     * ¿La edición toca algún campo traducible? Si no, no se crea una traducción
     * vacía para ese idioma.
     */
    private static boolean touchesTranslation(AdminProductQuickEditDtoIn req) {
        return Texts.has(req.getTitle()) || req.getShortDescription() != null || req.getDescription() != null
                || req.getMetaTitle() != null || req.getMetaDescription() != null;
    }

    /**
     * Traducción del idioma activo, creándola si el producto todavía no la tiene.
     */
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
        // DROP-681: external_id es varchar(120). Con externalId largos (BULK-…),
        // "-COPY-…" lo
        // desbordaba y el insert fallaba (500). Se capa la base para que el resultado
        // quepa en 120.
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
                    .add(ProductTranslationEntity.builder().product(saved).language(tr.getLanguage())
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
        // Slugify descarta lo que no sea ASCII: con un título íntegramente en chino (o
        // en cualquier otra
        // escritura no latina) devuelve "" y el slug quedaba en "-<externalId>". Se usa
        // un prefijo neutro
        // para que nunca empiece por guion; quien tenga el título en escritura latina
        // (el importador
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
        // Reindexar también arrastra el espejado: drena las imágenes PENDING (cada lote
        // reindexa) para que
        // "reindexar" deje todo el catálogo visible en el escaparate, no solo actualice
        // el índice.
        imageMirrorService.mirrorAllPendingAsync();
        return n;
    }

    @Override
    public ReindexStatus startReindex() {
        // tryAcquire() marca "en curso" de forma atómica; si ya había uno, no se lanza
        // otro.
        if (!reindexRunner.tryAcquire()) {
            return new ReindexStatus(true, false, reindexRunner.lastIndexed());
        }
        // Cruce de bean (runner distinto): así surte efecto el @Async y la petición
        // vuelve al instante.
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
     * Reconstruye los ejes (Color/Talla) de un producto a partir de las opciones de
     * sus variantes.
     *
     * <p>
     * Sólo actúa sobre el producto que NO tiene ejes pero SÍ variantes: si ya los
     * tiene son los que
     * declaró el proveedor y pisarlos con los derivados perdería nombres y orden
     * reales.
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
     * Nombre de eje → valores distintos, conservando el orden de aparición en las
     * variantes (por eso
     * LinkedHashMap/LinkedHashSet: ese orden es el que verá el usuario en el
     * selector de la ficha).
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

    /**
     * Eje derivado: el nombre no está traducido, así que se guarda igual como
     * canónico y como visible.
     */
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
                        && (tr.getMetaTitle() == null || tr.getMetaTitle().isBlank() || tr.getMetaDescription() == null
                                || tr.getMetaDescription().isBlank()
                // DROP-686: meta contaminado con CJK en idioma no-chino → regenerar.
                                || (!zh && (ProductSeoMetadata.hasCjk(tr.getMetaTitle())
                                        || ProductSeoMetadata.hasCjk(tr.getMetaDescription()))));
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
    // DROP-639: also evict the pricing-amount cache so the product's headline
    // (Resumen) price
    // recomputes from the new variant — otherwise it shows the stale pre-edit
    // value.
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
        VariantValueEntity v = variantValueRepository.findById(valueId)
                .orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
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
        VariantValueEntity v = variantValueRepository.findById(valueId)
                .orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
        VariantOptionEntity opt = v.getOption();
        UUID productId = (opt != null && opt.getProduct() != null) ? opt.getProduct().getId() : null;
        if (productId != null) {
            // Nombre del eje tal como se guarda en product_variant.options_json
            // ("Color"/"Talla"/…).
            String optName = (opt.getName() != null && !opt.getName().isBlank()) ? opt.getName() : opt.getNameZh();
            // Borra las combinaciones (product_variant) que usan este valor en ese eje (por
            // etiqueta o canónico).
            jdbcTemplate.update(
                    "DELETE FROM product_variant WHERE product_id = ? AND (options_json->>? = ? OR options_json->>? = ?)",
                    productId, optName, v.getValueZh(), optName, v.getValue());
        }
        // Se saca de la colección del PADRE antes de borrarlo. No es redundante con el
        // delete: la
        // asociación es @OneToMany(cascade = ALL, orphanRemoval = true), así que
        // mientras el valor siga
        // dentro de `opt.values` Hibernate lo considera vivo. Y la colección se carga
        // sí o sí unas líneas
        // más abajo, cuando el indexador lee el producto ENTERO dentro de esta misma
        // transacción: al
        // materializarla, el valor marcado para borrar reaparecía y la cascada lo
        // volvía a persistir.
        // Resultado: el endpoint respondía 204 y la fila seguía en la base de datos.
        if (opt != null && opt.getValues() != null) {
            opt.getValues().remove(v);
        }
        variantValueRepository.delete(v);
        // Flush explícito: obliga a que el DELETE llegue a la base ANTES de que el
        // indexador vuelva a leer
        // el producto. Sin esto, el orden de las operaciones lo decide Hibernate y el
        // indexado podía
        // adelantarse al borrado.
        variantValueRepository.flush();
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
        VariantValueEntity v = variantValueRepository.findById(valueId)
                .orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
        // DROP-674: imagen real por color. Una cadena vacía la elimina (volverá a usar
        // la principal).
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
        VariantValueEntity v = variantValueRepository.findById(valueId)
                .orElseThrow(() -> new NotFoundException(VARIANT_VALUE));
        String lang = language.trim().toLowerCase();
        String val = value != null ? value.trim() : null;
        v.getTranslations().removeIf(tt -> lang.equalsIgnoreCase(tt.getLanguage()));
        if (val != null && !val.isEmpty()) {
            v.getTranslations()
                    .add(VariantValueTranslationEntity.builder().variantValue(v).language(lang).value(val).build());
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
    public ProductImageView addProductImage(UUID productId, String url, String role) {
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
        // Si la URL ya apunta a NUESTRO storage (S3/MinIO) está lista → MIRRORED. Si es
        // externa
        // (1688/alicdn u otro CDN), la dejamos PENDING para que el ImageMirrorService
        // la descargue y la
        // suba a S3 (y deje de depender del hotlink). Persist vía el repo para devolver
        // el id generado.
        boolean alreadyOurs = url != null && objectStorage.publicUrl() != null && !objectStorage.publicUrl().isBlank()
                && url.startsWith(objectStorage.publicUrl());
        ProductImageEntity img = ProductImageEntity.builder().product(product).position(nextPos)
                .role(asMain ? "MAIN" : GALLERY).sourceUrl(url).cdnUrl(alreadyOurs ? url : null)
                .mirrorStatus(alreadyOurs ? MirrorStatus.MIRRORED : MirrorStatus.PENDING).build();
        ProductImageEntity saved = imageRepository.save(img);
        productIndexer.indexProduct(productId);
        // Edición: si la imagen añadida es externa (PENDING), espejarla YA para que se
        // vea al instante.
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
        // cambiarVideoUrl y no setVideoUrl: desde que el vídeo se espeja, la ficha PREFIERE
        // videoCdnUrl sobre videoUrl. Limpiando solo la dirección del proveedor quedaba la copia en
        // nuestro almacenamiento y el reproductor la seguía sirviendo: el vídeo se borraba de la base
        // y se seguía viendo en la ficha.
        product.cambiarVideoUrl(null);
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
        // Product.images es @OneToMany(orphanRemoval=true): NO basta con
        // imageRepository.delete(img),
        // porque el producto gestionado sigue referenciando la imagen en su colección y
        // Hibernate la
        // re-asocia en el flush (el borrado "se pierde" → respondía 204 pero quedaban
        // las 4). Hay que
        // QUITARLA de la colección del padre; orphanRemoval emite entonces el DELETE.
        // Forzamos el flush
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
        if (sonTodasDeDetalle(product, imageIds)) {
            reordenaElDetalle(product, imageIds);
        } else {
            reordenaLaGaleria(product, imageIds);
        }
        imageRepository.flush();
        productIndexer.indexProduct(productId);
    }

    /**
     * ¿La lista recibida son SOLO fotos de la descripción?
     *
     * <p>El escaparate tiene dos galerías independientes —el carrusel y las fotos de la descripción—
     * y cada una manda sus propios identificadores, así que una lista nunca las mezcla. Distinguirlas
     * por el papel de lo que llega evita un segundo camino en la API para hacer lo mismo.
     */
    private boolean sonTodasDeDetalle(ProductEntity product, List<UUID> imageIds) {
        Map<UUID, String> papelPorId = new HashMap<>();
        for (ProductImageEntity img : product.getImages()) {
            papelPorId.put(img.getId(), img.getRole());
        }
        for (UUID id : imageIds) {
            if (!DETAIL.equalsIgnoreCase(papelPorId.get(id))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reordena SOLO las fotos de la descripción, dentro de los huecos que ya ocupaban.
     *
     * <p>No se puede reutilizar el reordenado del carrusel: aquel reasigna el papel por posición —la
     * primera a MAIN y el resto a GALLERY—, así que arrastrar un cartel de medidas en la sección de
     * detalle lo habría convertido en la foto de portada del producto y lo habría sacado de su
     * sección. Aquí el papel no se toca y el carrusel ni se entera: las fotos de la descripción viven
     * detrás de la galería en la misma secuencia de posiciones, y solo se permutan entre ellas.
     */
    private void reordenaElDetalle(ProductEntity product, List<UUID> imageIds) {
        List<ProductImageEntity> detalle = product.getImages().stream()
                .filter(img -> DETAIL.equalsIgnoreCase(img.getRole()))
                .sorted(Comparator.comparingInt(ProductImageEntity::getPosition)).toList();
        List<Integer> huecos = detalle.stream().map(ProductImageEntity::getPosition).toList();

        Map<UUID, ProductImageEntity> porId = new HashMap<>();
        for (ProductImageEntity img : detalle) {
            porId.put(img.getId(), img);
        }
        // Primero las pedidas, en el orden pedido; detrás, las que no venían en la lista, con su
        // orden de antes. Así una lista incompleta no deja a nadie sin hueco ni duplica ninguno.
        List<ProductImageEntity> nuevoOrden = new ArrayList<>();
        for (UUID id : imageIds) {
            ProductImageEntity img = porId.remove(id);
            if (img != null) {
                nuevoOrden.add(img);
            }
        }
        for (ProductImageEntity img : detalle) {
            if (porId.containsKey(img.getId())) {
                nuevoOrden.add(img);
            }
        }
        for (int i = 0; i < nuevoOrden.size(); i++) {
            nuevoOrden.get(i).setPosition(huecos.get(i));
        }
    }

    /**
     * Reordena el CARRUSEL: la primera pasa a ser la imagen principal del producto y el resto,
     * galería. Lo que no viene en la lista —las fotos de la descripción, sobre todo— se coloca detrás
     * conservando su orden previo y SIN cambiarle el papel.
     */
    private void reordenaLaGaleria(ProductEntity product, List<UUID> imageIds) {
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

    /**
     * Main image URL of an ingest payload (role MAIN first, then lowest position).
     */
    private String mainImageUrlOf(List<IngestImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream().filter(i -> i.sourceUrl() != null && !i.sourceUrl().isBlank())
                .min(Comparator.comparingInt((IngestImage i) -> "MAIN".equalsIgnoreCase(i.role()) ? 0 : 1)
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
                .min(Comparator.comparingInt((ProductImageEntity i) -> "MAIN".equalsIgnoreCase(i.getRole()) ? 0 : 1)
                        .thenComparingInt(ProductImageEntity::getPosition))
                .map(i -> i.getCdnUrl() != null && !i.getCdnUrl().isBlank() ? i.getCdnUrl() : i.getSourceUrl())
                .orElse(null);
    }

    /* ============ Bulk import (admin) ============ */

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public BulkResultDtoOut bulkCreateProducts(List<BulkProductDtoIn> rows) {
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
        // Reindexar SOLO lo recién creado (no los ~1000 existentes) y espejar sus
        // imágenes YA en background:
        // el mirror pone hasImage=true y reindexa → el producto aparece en el
        // escaparate casi al instante.
        for (UUID id : createdIds) {
            productIndexer.indexProduct(id);
        }
        mirrorNewImagesAfterCommit(createdIds);
        return new BulkResultDtoOut(created, failed, errors);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BulkProductDtoIn> exportProducts(int from, int to, ExportFilter filtro) {
        int safeFrom = Math.max(1, from);
        int safeTo = Math.max(safeFrom, to);
        return aBulk(buscaParaExportar(filtro, new Tramo(safeFrom - 1L, safeTo - safeFrom + 1)).getContent());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductExportBatch exportPage(int page, int size, ExportFilter filtro) {
        int safeSize = Math.clamp(size, 1, 1000);
        Page<ProductEntity> pagina = buscaParaExportar(filtro, PageRequest.of(Math.max(0, page), safeSize));
        return new ProductExportBatch(aBulk(pagina.getContent()), pagina.hasNext());
    }

    /**
     * La consulta que alimenta las TRES exportaciones, y es la MISMA que la lista del panel.
     *
     * <p>Antes cada exportación tenía su propio JPQL —dos, más una consulta nativa para el volcado—, y
     * solo sabían acotar por fecha y certificación. Por eso filtrar la lista a treinta productos y abrir
     * «Exportar» ofrecía los nueve mil: eran dos ideas distintas de qué es «el catálogo».
     *
     * <p>El orden por id es lo que hace que los tramos sean estables entre descargas: sin un desempate
     * determinista, dos segmentos consecutivos podrían repetir un producto y saltarse otro.
     */
    private Page<ProductEntity> buscaParaExportar(ExportFilter filtro, Pageable tramo) {
        ExportFilter f = filtro == null ? ExportFilter.todo() : filtro;
        ProductStatus estado = parseStatusTolerant(f.status());
        String needle = f.q() == null ? "" : f.q().trim().toLowerCase(Locale.ROOT);
        return productJpaRepository.searchAdmin(estado, f.categoryId(), needle, f.verified(), "es", false, f.minCost(),
                f.maxCost(), f.minSales(), f.minTrend(), f.createdFrom(), f.createdTo(), tramo);
    }

    /** Los hijos se traen POR LOTE con los ids de la página: uno por producto eran cinco consultas por fila. */
    private List<BulkProductDtoIn> aBulk(List<ProductEntity> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = products.stream().map(ProductEntity::getId).toList();
        Map<UUID, List<ProductAttributeEntity>> attributes = em
                .createQuery("SELECT a FROM ProductAttributeEntity a WHERE a.product.id IN :ids",
                        ProductAttributeEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(a -> a.getProduct().getId()));
        Map<UUID, List<ProductSpecificationEntity>> specs = em
                .createQuery(
                        "SELECT s FROM ProductSpecificationEntity s WHERE s.product.id IN :ids ORDER BY s.position",
                        ProductSpecificationEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        Map<UUID, List<ProductPriceTierEntity>> tiers = em
                .createQuery("SELECT t FROM ProductPriceTierEntity t WHERE t.product.id IN :ids ORDER BY t.minQty",
                        ProductPriceTierEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        Map<UUID, List<ProductReviewEntity>> reviews = em
                .createQuery("SELECT r FROM ProductReviewEntity r WHERE r.product.id IN :ids ORDER BY r.createdAt",
                        ProductReviewEntity.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        List<BulkProductDtoIn> out = new ArrayList<>();
        for (ProductEntity p : products) {
            out.add(bulkExportMapper.toBulk(p, attributes.getOrDefault(p.getId(), List.of()),
                    specs.getOrDefault(p.getId(), List.of()), tiers.getOrDefault(p.getId(), List.of()),
                    reviews.getOrDefault(p.getId(), List.of())));
        }
        return out;
    }

    /**
     * Un tramo con desplazamiento ARBITRARIO.
     *
     * <p>`PageRequest` solo sabe de páginas enteras, y los tramos del panel se piden por posición
     * (1-1000, 1001-2000…). Con un tamaño de segmento que no divida al desplazamiento, `PageRequest`
     * devolvería otra franja sin avisar.
     */
    private record Tramo(long offset, int size) implements Pageable {
        @Override
        public int getPageNumber() {
            return (int) (offset / Math.max(1, size));
        }

        @Override
        public int getPageSize() {
            return size;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return Sort.by(Sort.Direction.ASC, "id");
        }

        @Override
        public Pageable next() {
            return new Tramo(offset + size, size);
        }

        @Override
        public Pageable previousOrFirst() {
            return offset <= size ? first() : new Tramo(offset - size, size);
        }

        @Override
        public Pageable first() {
            return new Tramo(0, size);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            return new Tramo((long) pageNumber * size, size);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BulkProductDtoIn exportProduct(UUID id) {
        ProductEntity p = productJpaRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(PRODUCT_NOT_FOUND + id));
        return aBulk(List.of(p)).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public long countProducts(ExportFilter filtro) {
        // Cuenta con la MISMA consulta que exporta: si contara por su cuenta, los segmentos que el panel
        // ofrece no cuadrarían con lo que después se descarga. Pide una sola fila porque solo interesa el
        // total que calcula la paginación.
        return buscaParaExportar(filtro, PageRequest.of(0, 1)).getTotalElements();
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
     * Espeja YA (en background, tras el commit) las imágenes PENDING de los
     * productos indicados, y reindexa.
     * Se llama en TODA operación que introduce imágenes de producto —alta
     * individual, alta masiva y edición
     * (añadir imagen)— para que el producto aparezca en el escaparate casi al
     * instante. Si hay transacción
     * activa, se difiere a {@code afterCommit} (si no, el hilo async no vería las
     * filas aún sin commitear);
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
                    espejarMedios(ids);
                }
            });
        } else {
            espejarMedios(ids);
        }
    }

    /**
     * Lleva a nuestro almacenamiento las fotos y el vídeo de lo que se acaba de guardar.
     *
     * <p>Los dos van juntos y ninguno puede tumbar al otro: si el vídeo no se puede traer —pesa de más,
     * el origen no responde— las fotos ya están, y al revés. Cada servicio se encarga de reintentar lo
     * suyo por su cuenta.
     */
    private void espejarMedios(List<UUID> ids) {
        try {
            imageMirrorService.mirrorProductsAsync(ids);
        } catch (Exception e) {
            log.debug("Espejado de imágenes tras guardar falló: {}", e.toString());
        }
        try {
            videoMirrorService.mirrorProductsAsync(ids);
        } catch (Exception e) {
            log.debug("Espejado de vídeo tras guardar falló: {}", e.toString());
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
     * Aplica una acción a cada id y acumula los fallos sin cortar el lote. Cada
     * elemento va en su propia
     * transacción —la abre el método invocado A TRAVÉS DE {@code self}, no con
     * {@code this}: por
     * autoinvocación el proxy no interviene y no se abriría ninguna—, así que un
     * fallo no arrastra a los
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
            // NOSONAR java:S2077 — lo único concatenado es el nombre de tabla, que sale de
            // la constante
            // PRODUCT_CHILD_TABLES del propio código; el valor va como parámetro.
            jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", id); // NOSONAR
        }
        productJpaRepository.delete(p);
        productIndexer.deleteFromIndex(id);
        log.info("::> [CATALOG] Product deleted id={}", id);
    }

    /**
     * Rellena el campo fijo de un idioma (título/descr.) desde el mapa
     * `translations` si está vacío.
     */
    private void mergeTranslationField(String lang, Map<String, BulkProductDtoIn.BulkTranslation> m,
            Supplier<String> getTitle, Consumer<String> setTitle, Supplier<String> getDesc, Consumer<String> setDesc) {
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

    /**
     * Builds the heavy ingest request from a friendly row, persists it (via the
     * writer) and returns its id.
     */
    private UUID buildAndWriteProduct(BulkProductDtoIn r, List<SupplierEntity> suppliers) {
        CategoryEntity cat = resolveBulkCategory(r);
        // DROP-670: si la categoría define atributos obligatorios, el producto debe
        // traerlos (integridad).
        BulkProductRules.assertRequiredAttributes(r,
                categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(cat.getId()), cat.getSlug());
        // El proveedor se toma de supplierName; si no, del fabricante (manufacturer).
        // Solo si no hay
        // ninguno se usa el primero por defecto.
        String supName = Texts.firstNonBlankOr(r.getManufacturer(), r.getSupplierName());
        UUID supplierId = resolveBulkSupplier(suppliers, r.getSupplierExternalId(), supName);
        fillCanonicalContentFromTranslations(r);
        String esTitle = r.getTitleEs();
        // DROP-682: el título es obligatorio en al menos un idioma; mensaje claro (no
        // genérico).
        BulkProductRules.assertTitle(esTitle);
        String enTitle = Texts.firstNonBlankOr(esTitle, r.getTitleEn());
        String zhTitle = Texts.firstNonBlankOr(esTitle, r.getTitleZh());
        String ptTitle = Texts.firstNonBlankOr(esTitle, r.getTitlePt());
        String esDesc = Texts.firstNonBlankOr(esTitle, r.getDescriptionEs());
        // DROP-680: el precio es dato real obligatorio; no se inventa. Envío e IVA
        // (CNY) también, porque
        // sin ellos no se puede calcular el total (base×margen + iva + envío).
        BigDecimal price = BulkProductRules.resolvePrice(r, esTitle);
        BulkProductRules.assertShippingAndVat(r, esTitle);
        // external_id es varchar(120): con títulos largos el slug autogenerado lo
        // desbordaba.
        String externalId = BulkProductRules.externalIdOf(r, esTitle, SLUG::slugify, System.nanoTime());
        deleteRebuiltChildRows(r, externalId);
        List<IngestImage> images = ingestImagesOf(r, esTitle);
        // Ejes de variación (Color/Talla), variantes comprables y tramos de precio: se
        // derivan de la
        // fila sin inventar nada (ver BulkProductStructure).
        List<IngestVariantOption> options = BulkProductStructure.variantOptionsOf(r);
        List<IngestVariant> variants = BulkProductStructure.variantsOf(r, externalId, esTitle, price);
        List<IngestPriceTier> tiers = BulkProductStructure.priceTiersOf(r, price);
        // DROP-680: rating, recompra y reseñas NO se inventan. Si el proveedor no los
        // declara quedan
        // nulos/0; el desglose real (ratingBreakdown) se persiste y deriva
        // reviewCount/rating en applyLogistics.
        IngestProductRequest req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc,
                r.getManufacturer(), r.getMoq() != null ? r.getMoq() : 1, price, "CNY", r.getWeightGrams(),
                r.getMonthlySales() != null ? r.getMonthlySales() : 0, null, r.getRating(), 0,
                Texts.firstNonBlankOr("https://detail.1688.com/offer/" + externalId + ".html",
                        r.getSourceUrl() != null ? r.getSourceUrl().trim() : null),
                supplierId, cat.getId(), images, options, variants, tiers);
        // El writer corre @Transactional: aplica títulos+descripciones por idioma y la
        // logística
        // (vía el hook) sobre la entidad gestionada, evitando LazyInitialization.
        UUID id = catalogFillWriter.write(req, esTitle, enTitle, ptTitle, zhTitle, esDesc, r.getDescriptionEn(),
                r.getDescriptionPt(), r.getDescriptionZh(), p -> applyLogistics(p, r));
        applyRequestedDraftStatus(id, r);
        createBulkReviews(id, r.getReviews());
        return id;
    }

    /**
     * Idiomas ilimitados: si el contenido viene en el mapa {@code translations},
     * rellena los campos
     * canónicos es/en/pt/zh donde falten, porque de ellos salen el slug, la
     * validación y lo que escribe
     * el writer; el resto de idiomas del mapa se upsertan después en
     * {@link #applyLogistics}.
     *
     * <p>
     * Si no hay 'es' explícito se toma el primer idioma con título como canónico:
     * el alta exige
     * titleEs y, sin este respaldo, una fila perfectamente válida en inglés se
     * rechazaría.
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
        mergeTranslationField("fr", m, r::getTitleFr, r::setTitleFr, r::getDescriptionFr, r::setDescriptionFr);
        mergeTranslationField("it", m, r::getTitleIt, r::setTitleIt, r::getDescriptionIt, r::setDescriptionIt);
        mergeTranslationField("de", m, r::getTitleDe, r::setTitleDe, r::getDescriptionDe, r::setDescriptionDe);
        mergeTranslationField("nl", m, r::getTitleNl, r::setTitleNl, r::getDescriptionNl, r::setDescriptionNl);
        if (Texts.has(r.getTitleEs())) {
            return;
        }
        BulkProductDtoIn.BulkTranslation any = m.values().stream().filter(t -> t != null && Texts.has(t.getTitle()))
                .findFirst().orElse(null);
        if (any == null) {
            return;
        }
        r.setTitleEs(any.getTitle().trim());
        if (!Texts.has(r.getDescriptionEs())) {
            r.setDescriptionEs(any.getDescription());
        }
    }

    /**
     * UPSERT idempotente por externalId: el producto se ACTUALIZA EN SITIO (mismo
     * id, se preservan
     * enlaces, favoritos y pedidos). El producto y sus traducciones ya los upsertan
     * upsertProduct y el
     * writer; aquí sólo se limpian las COLECCIONES HIJAS que se reconstruyen
     * (variantes, opciones,
     * imágenes, atributos, fichas técnicas y tramos) por product_id ANTES de
     * recrearlas, para no
     * duplicarlas al reimportar. El producto padre NO se borra, así que su id no
     * cambia (a diferencia
     * de un delete + create).
     */
    private void deleteRebuiltChildRows(BulkProductDtoIn r, String externalId) {
        if (!Texts.has(r.getExternalId())) {
            return;
        }
        productJpaRepository.findFirstByExternalId(externalId).ifPresent(existing -> {
            UUID exId = existing.getId();
            for (String table : PRODUCT_CHILD_TABLES) {
                // NOSONAR java:S2077 — nombre de tabla de una constante del código; valor
                // parametrizado.
                jdbcTemplate.update("DELETE FROM " + table + " WHERE product_id = ?", exId); // NOSONAR
            }
        });
    }

    /**
     * Imágenes del producto. Se aceptan varias claves (imageUrls/images/photos/...
     * vía @JsonAlias) y el
     * atajo {@code imageUrl} (string suelto). Si no hay NINGUNA a nivel de producto
     * se usan como respaldo
     * las de las variantes (o las de los valores/colores del eje), para no rechazar
     * un producto cuya
     * única imagen vive en la variante. La primera posición es la MAIN, el resto
     * galería.
     */
    private static List<IngestImage> ingestImagesOf(BulkProductDtoIn r, String esTitle) {
        List<IngestImage> images = new ArrayList<>();
        int position = 0;
        for (String url : BulkProductRules.imageUrlsOf(r, esTitle)) {
            images.add(new IngestImage(url, position, position == 0 ? "MAIN" : GALLERY));
            position++;
        }
        // Las de la descripción, DETRÁS de la galería y con su propio rol. La posición sigue la misma
        // cuenta para que el orden dentro de cada grupo se conserve al reordenarlas desde el panel:
        // son filas de product_image como las demás, así que heredan espejado, compresión y edición.
        for (String url : BulkProductRules.detailImageUrlsOf(r)) {
            images.add(new IngestImage(url, position, DETAIL));
            position++;
        }
        return images;
    }

    /**
     * El writer publica como ACTIVE por defecto; sólo se degrada a DRAFT si el
     * operador lo pidió.
     */
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

    /**
     * Crea las reseñas reales del producto desde la carga masiva (cada una con su
     * idioma).
     */
    private void createBulkReviews(UUID productId, List<BulkProductDtoIn.BulkReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        ProductEntity ref = productJpaRepository.findById(productId).orElse(null);
        if (ref == null) {
            return;
        }
        for (BulkProductDtoIn.BulkReview rv : reviews) {
            // Una reseña sin cuerpo NI título no dice nada al comprador: se descarta en vez
            // de guardarla vacía.
            if (Texts.has(rv.getBody()) || Texts.has(rv.getTitle())) {
                productReviewJpaRepositoryAdapter.save(toReviewEntity(ref, rv));
            }
        }
    }

    /**
     * Reseña real de la carga. Sin autor se firma como "Anónimo" y sin idioma se
     * asume español (es el
     * idioma por defecto del escaparate); la puntuación se acota a 1..5, que es lo
     * que pinta la ficha.
     */
    private static ProductReviewEntity toReviewEntity(ProductEntity product, BulkProductDtoIn.BulkReview rv) {
        short rating = rv.getRating() != null ? (short) Math.clamp(rv.getRating(), 1, 5) : 5;
        return ProductReviewEntity.builder().product(product)
                .authorName(Texts.has(rv.getAuthorName()) ? rv.getAuthorName().trim() : "Anónimo")
                .authorCountry(rv.getAuthorCountry()).rating(rating).title(rv.getTitle()).body(rv.getBody())
                .tags(rv.getTags() != null ? String.join(",", rv.getTags()) : null)
                // Una reseña que llega en la carga del catálogo NO puede marcarse como compra
                // verificada,
                // diga lo que diga el fichero de origen: no hay ninguna compra en esta tienda
                // detrás de
                // ella. Afirmar lo contrario está en la lista negra de prácticas desleales de
                // la
                // Directiva Omnibus, que se sanciona sin necesidad de probar que alguien fue
                // engañado.
                // El distintivo se gana en ProductReviewUseCase, cuando escribe quien sí
                // compró.
                .verifiedPurchase(false).source(ReviewSource.SUPPLIER).approved(true)
                .language(Texts.has(rv.getLanguage()) ? rv.getLanguage().trim().toLowerCase() : "es").build();
    }

    /**
     * Fija los campos de logística/aduana sobre la entidad gestionada (dentro de la
     * transacción del writer).
     */
    /**
     * El margen interno de la ficha que llega, en porcentaje.
     *
     * <p><b>Compatibilidad, y no es opcional.</b> Hasta el 25-sep-2026 esto viajaba como
     * {@code ivaCny}, un importe absoluto. Siguen circulando fichas con el campo viejo por dos vías
     * que no se pueden reescribir: los JSON de carga que ya están guardados, y los eventos que están
     * AHORA MISMO en el bus —el tema retiene 30 días—. Si el importador dejara de entenderlo, esas
     * fichas entrarían con el margen a nulo y se venderían con el porcentaje por defecto en vez del
     * suyo, sin un solo error.
     *
     * <p>La conversión es la misma que hizo la migración: el importe dividido entre la base. Sin base
     * no hay de qué sacar el porcentaje, y se deja a nulo para que el cálculo use el de por defecto.
     */
    private static BigDecimal margenInternoDe(BulkProductDtoIn r) {
        if (r.getMargenInternoPct() != null) {
            return r.getMargenInternoPct();
        }
        BigDecimal importeViejo = r.getIvaCny();
        BigDecimal base = r.getPrice();
        if (importeViejo == null || base == null || base.signum() <= 0) {
            return null;
        }
        return importeViejo.multiply(BigDecimal.valueOf(100)).divide(base, 3, RoundingMode.HALF_UP);
    }

    private void applyLogistics(ProductEntity p, BulkProductDtoIn r) {
        // Envío e IVA (CNY): obligatorios en la carga; se suman al total SIN margen
        // (ver PricingService).
        p.setShippingCny(r.getShippingCny());
        p.setMargenInternoPct(margenInternoDe(r));
        // Recargo fijo (CNY): opcional, default 0. Se aplica en la importación para que el recargo
        // llegue igual por el bus/reexport (si no, el destino lo dejaría a 0).
        //
        // Ausente vale 0, NUNCA null: la columna es NOT NULL desde la v157, así que un JSON de carga
        // sin este campo —que es lo normal, es opcional— tumbaba el alta entera con un 23502 y el
        // producto no se creaba. Mismo cuidado que con las dos bolsas de abajo.
        BulkProductFields.applySurcharge(p, r);
        // Las bolsas de subvención llegan por el bulk y por el bus; ausentes valen 0, nunca null, que la
        // columna es NOT NULL y el cálculo del checkout las suma sin preguntar.
        p.setShippingUserCny(r.getShippingUserCny() != null ? r.getShippingUserCny() : BigDecimal.ZERO);
        p.setDutyUserCny(r.getDutyUserCny() != null ? r.getDutyUserCny() : BigDecimal.ZERO);
        // La categoría que 1688 declara, guardada tal cual y sin intervenir en dónde se archiva —de eso
        // se encarga la categoría propia—. Se guarda para poder AUDITAR esa decisión después: hasta
        // ahora no quedaba rastro del origen, así que revisar si un producto estaba bien clasificado
        // solo se podía hacer contra el título, el mismo dato con el que se equivocó el clasificador.
        p.setCategory1688Id(Texts.trimToNull(r.getCategory1688Id()));
        p.setCategory1688Name(Texts.trimToNull(r.getCategory1688Name()));
        BulkProductFields.applyPackageDimensions(p, r);
        BulkProductFields.applyCustomsFields(p, r);
        // Lo que la carga no traiga (partida arancelaria, material, uso, batería y
        // medidas del paquete) se
        // completa con el perfil de la categoría: sin esos datos el envío no se puede
        // cotizar ni declarar.
        customsProfileService.applyDefaults(p,
                p.getCategory() != null ? p.getCategory().getSlug() : r.getCategorySlug());
        BulkProductFields.applyCommercialFields(p, r);
        BulkProductFields.applyRatingBreakdown(p, r);
        // supplierSkuId + peso/dimensiones por variante (DROP-675): se emparejan por
        // SKU sobre las
        // variantes ya creadas por upsertProduct.
        BulkProductFields.applyVariantLogistics(p, r);
        BulkProductFields.applyVariantValueTranslations(p, r);
        replaceProductAttributes(p, r);
        replaceProductSpecifications(p, r);
        BulkProductFields.applyExtraTranslations(p, r);
        // Las traducciones (título + descripción por idioma) las fija el writer dentro
        // de su transacción.
        // DROP-679: como el writer publica el producto (status ACTIVE), generamos aquí
        // el SEO por idioma
        // a partir de esas traducciones reales (las colecciones ya están adjuntas a la
        // entidad gestionada).
        ProductSeoMetadata.generate(p);
    }

    /**
     * Atributos taxonómicos (las facetas del buscador): se reemplazan ENTEROS en
     * cada import. Acumularlos
     * dejaría conviviendo el valor viejo y el nuevo, y el filtro devolvería el
     * producto por los dos.
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
     * <p>
     * La posición de respaldo avanza con TODAS las filas leídas, no sólo con las
     * guardadas: así una
     * fila incompleta descartada no reordena las siguientes respecto al fichero
     * original.
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
                        .locale(Texts.firstNonBlankOr("es", s.getLocale()).trim().toLowerCase()).specKey(s.getKey())
                        .specValue(s.getValue()).position(s.getPosition() != null ? s.getPosition() : fallbackPosition)
                        .createdAt(Instant.now()).build());
            }
            fallbackPosition++;
        }
    }

    @Override
    @Caching(evict = {@CacheEvict(value = CACHE_CATEGORY_TREE, allEntries = true),
            @CacheEvict(value = CACHE_CATEGORIES_FLAT, allEntries = true)})
    public BulkResultDtoOut bulkCreateCategories(List<BulkCategoryDtoIn> rows) {
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
     * Alta o actualización de UNA categoría de la carga. Los nombres que falten
     * caen al español, el único
     * obligatorio. El padre se resuelve por slug: uno listado antes en el mismo
     * lote ya está persistido y
     * se ve desde aquí; un slug desconocido tumba SÓLO esta fila (lo recoge el
     * catch del bucle).
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
                r.getIcon() != null ? r.getIcon() : "tag", Map.of("es", r.getNameEs(), "en", en, "pt", pt)));
    }

    /**
     * DROP-677: resuelve la categoría interna del producto al importar. Prioridad:
     * (1) categorySlug
     * interno explícito; (2) mapeo por id de 1688; (3) mapeo por nombre de 1688. Si
     * nada resuelve, se
     * rechaza la fila (no se inventa una categoría).
     */
    private CategoryEntity resolveBulkCategory(BulkProductDtoIn r) {
        if (r.getCategorySlug() != null && !r.getCategorySlug().isBlank()) {
            String slug = r.getCategorySlug().trim();
            return categoryRepository.findBySlug(slug)
                    .orElseThrow(() -> new BusinessException("Categoría no encontrada: " + slug));
        }
        if (r.getCategory1688Id() != null && !r.getCategory1688Id().isBlank()) {
            Optional<Category1688MappingEntity> m = category1688MappingRepository
                    .findByExternal1688Id(r.getCategory1688Id().trim());
            if (m.isPresent()) {
                return m.get().getCategory();
            }
        }
        if (r.getCategory1688Name() != null && !r.getCategory1688Name().isBlank()) {
            Optional<Category1688MappingEntity> m = category1688MappingRepository
                    .findFirstByExternal1688NameIgnoreCase(r.getCategory1688Name().trim());
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
        Optional<Category1688MappingEntity> existing = category1688MappingRepository
                .findByExternal1688Id(external1688Id.trim());
        Category1688MappingEntity m = existing.orElseGet(Category1688MappingEntity::new);
        m.setExternal1688Id(external1688Id.trim());
        m.setExternal1688Name(external1688Name != null && !external1688Name.isBlank() ? external1688Name.trim() : null);
        m.setCategory(cat);
        return category1688MappingRepository.save(m).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Category1688MappingDtoOut> listCategory1688Mappings() {
        return category1688MappingRepository.findAll().stream()
                .map(m -> new Category1688MappingDtoOut(m.getId(), m.getExternal1688Id(), m.getExternal1688Name(),
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
    public List<CategoryAttributeSchemaDtoOut> listCategoryAttributeSchema(UUID categoryId) {
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
        Optional<CategoryAttributeSchemaEntity> existing = categoryAttributeSchemaRepository
                .findByCategory_IdAndAttrKey(categoryId, attrKey.trim());
        CategoryAttributeSchemaEntity s = existing.orElseGet(CategoryAttributeSchemaEntity::new);
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

    /**
     * Nombre legible de una categoría: traducción ES, si no nameZh, si no el slug.
     */
    private String categoryDisplayName(CategoryEntity c) {
        if (c == null) {
            return null;
        }
        return c.getTranslations().stream().filter(tr -> "es".equalsIgnoreCase(tr.getLanguage())).findFirst()
                .map(CategoryTranslationEntity::getName).filter(n -> n != null && !n.isBlank())
                .orElse(c.getNameZh() != null ? c.getNameZh() : c.getSlug());
    }

    private UUID resolveBulkSupplier(List<SupplierEntity> suppliers, String supplierExternalId, String supplierName) {
        if (supplierExternalId != null && !supplierExternalId.isBlank()) {
            String ext = supplierExternalId.trim();
            Optional<SupplierEntity> existing = supplierRepository.findBySourceAndExternalId("1688", ext);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
            // El proveedor de 1688 aún no existe: se crea con ese externalId y el nombre
            // disponible
            // (supplierName/manufacturer) en vez de rechazar la fila. Así el import es
            // autosuficiente.
            String name = (supplierName != null && !supplierName.isBlank())
                    ? supplierName.trim()
                    : ("Proveedor " + ext);
            String extId = ext.length() > 100 ? ext.substring(0, 100) : ext;
            return supplierRepository.save(SupplierEntity.builder().source("1688").externalId(extId).name(name)
                    .country("CN").verified(false).trustPass(false).build()).getId();
        }
        // Si se da el nombre del proveedor/fábrica, buscar o CREAR uno con ese nombre —
        // no reutilizar
        // el primer proveedor por defecto (causaba que una camiseta apuntara a la
        // fábrica de zapatos).
        if (supplierName != null && !supplierName.isBlank()) {
            String name = supplierName.trim();
            return supplierRepository.findFirstByNameIgnoreCase(name).map(SupplierEntity::getId).orElseGet(() -> {
                String ext = "NAME-" + SLUG.slugify(name);
                if (ext.length() > 100) {
                    ext = ext.substring(0, 100);
                }
                return supplierRepository.save(SupplierEntity.builder().source("1688").externalId(ext).name(name)
                        .country("CN").verified(false).trustPass(false).build()).getId();
            });
        }
        if (suppliers.isEmpty())
            throw new BusinessException("No hay proveedores; crea uno antes de importar productos");
        return suppliers.get(0).getId();
    }

    /**
     * Los anuncios al bus que se dieron por perdidos, del más reciente al más antiguo.
     *
     * <p>Certificar un producto responde al instante porque el envío al bus va diferido; el precio de
     * eso es que un fallo ya no cabe en la respuesta de la petición. Aquí es donde se ve.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AnuncioBusFallidoView> anunciosAlBusFallidos() {
        return productJpaRepository.findTop100ByBusEstadoOrderByUpdatedAtDesc(BusAnuncioEstado.FALLIDO).stream()
                .map(p -> new AnuncioBusFallidoView(p.getId(), p.getExternalId(), p.getSlug(), tituloCorto(p),
                        p.getBusIntentos() == null ? 0 : p.getBusIntentos(), p.getBusError(), p.getUpdatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public int reintentarAnunciosAlBusFallidos() {
        int reencolados = productJpaRepository.reencolarAnunciosFallidos();
        log.info("Anuncio al bus: {} producto(s) vuelven a la cola por petición del admin", reencolados);
        return reencolados;
    }

    /** Con qué nombre se reconoce el producto en la lista de fallos: español, inglés o el chino original. */
    private String tituloCorto(ProductEntity p) {
        if (p.getTranslations() == null) {
            return p.getTitleZh();
        }
        return p.getTranslations().stream()
                .filter(tr -> "es".equalsIgnoreCase(tr.getLanguage()) || "en".equalsIgnoreCase(tr.getLanguage()))
                .map(ProductTranslationEntity::getTitle).filter(s -> s != null && !s.isBlank()).findFirst()
                .orElse(p.getTitleZh());
    }

}
