package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedSupplier;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORIES_FLAT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORY_TREE;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared storefront catalog read-projection helper. Holds the category/supplier/
 * variant view logic and the SQL-backed product listing so that both
 * {@code StorefrontCatalogController} and {@code PartnerCatalogController} expose
 * identical shapes without one controller injecting the other (the partner→storefront
 * controller dependency is replaced by this shared collaborator + the CatalogUseCase).
 */
@Service
@RequiredArgsConstructor
public class CatalogStorefrontReadService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierSearchService supplierSearchService;
    private final ProductVariantRepository variantRepository;
    private final ProductMapper productMapper;

    /* ============================ Categories ============================ */

    // Cacheado por idioma (Caffeine 5 min / Redis): el catálogo de categorías cambia poco y lo consultan
    // muchos usuarios a la vez. Spring+Caffeine coalescen los fallos de caché (solo 1 computa, el resto
    // espera), evitando estampida con 30K usuarios concurrentes. Se invalida al crear/editar/borrar.
    @Cacheable(value = CACHE_CATEGORIES_FLAT, key = "#lang")
    @Transactional(readOnly = true)
    public List<CategoryView> categoriesFlat(String lang) {
        Map<UUID, Long> counts = productCountByCategory();
        return categoryRepository.findByParentIsNullOrderByPositionAsc().stream()
                .map(c -> viewWithCount(c, lang, List.of(), counts)).toList();
    }

    @Cacheable(value = CACHE_CATEGORY_TREE, key = "#lang")
    @Transactional(readOnly = true)
    public List<CategoryView> categoriesTree(String lang) {
        // O(n): one query for all categories (+translations), one GROUP BY for counts, tree built in
        // memory. Avoids the per-node COUNT and the translations N+1 that made the cold build ~3s.
        List<CategoryEntity> all = categoryRepository.findAllWithTranslations();
        Map<UUID, Long> counts = productCountByCategory();
        Map<UUID, List<CategoryEntity>> byParent = new HashMap<>();
        List<CategoryEntity> roots = new ArrayList<>();
        for (CategoryEntity c : all) {
            if (c.getParent() == null) {
                roots.add(c);
            } else {
                byParent.computeIfAbsent(c.getParent().getId(), k -> new ArrayList<>()).add(c);
            }
        }
        roots.sort(Comparator.comparingInt(CategoryEntity::getPosition));
        return roots.stream().map(r -> treeView(r, lang, byParent, counts)).toList();
    }

    /** Builds a category view (with its descendants) from the in-memory parent→children index. */
    private CategoryView treeView(CategoryEntity c, String lang, Map<UUID, List<CategoryEntity>> byParent,
            Map<UUID, Long> counts) {
        List<CategoryEntity> kids = byParent.getOrDefault(c.getId(), List.of());
        List<CategoryView> children = kids.stream().sorted(Comparator.comparingInt(CategoryEntity::getPosition))
                .map(ch -> treeView(ch, lang, byParent, counts)).toList();
        return viewWithCount(c, lang, children, counts);
    }

    private CategoryView viewWithCount(CategoryEntity c, String lang, List<CategoryView> children,
            Map<UUID, Long> counts) {
        long count = counts.getOrDefault(c.getId(), 0L);
        return new CategoryView(c.getId(), c.getSlug(), translatedName(c, lang), c.getNameZh(),
                c.getParent() != null ? c.getParent().getId() : null, c.getPosition(), c.getIcon(), (int) count,
                children);
    }

    /** Product counts per category resolved in a single GROUP BY query. */
    private Map<UUID, Long> productCountByCategory() {
        Map<UUID, Long> map = new HashMap<>();
        for (Object[] row : categoryRepository.productCountByCategory()) {
            map.put((UUID) row[0], (Long) row[1]);
        }
        return map;
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

    @Cacheable(value = CACHE_PRODUCT_LIST,
            key = "'cat:' + #idOrSlug + ':' + #page + ':' + #size + ':' + #lang + ':' + #sort + ':' "
                    + "+ T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsByCategory(String idOrSlug, int page, int size, String lang,
            String sort) {
        UUID categoryId = resolveCategory(idOrSlug).getId();
        return productList(page, size, lang, null, categoryId, null, null, null, sort);
    }

    /* ============================ Suppliers ============================ */

    @Transactional(readOnly = true)
    public List<SupplierView> suppliers() {
        // Servido desde OpenSearch (índice `suppliers`, mantenido en sync por SupplierIndexer en cada
        // alta/edición/baja); el productCount va embebido en el documento. Si el índice está vacío o
        // OpenSearch no responde, cae a la BD (misma proyección) — igual que categorías/productos.
        Optional<List<IndexedSupplier>> indexed = supplierSearchService.listFromIndex(null);
        if (indexed.isPresent()) {
            return indexed.get().stream()
                    .map(s -> new SupplierView(s.id(), s.externalId(), s.name(), s.nameZh(), s.country(), s.city(),
                            s.rating(), s.yearsActive(), s.verified(), s.trustPass(), s.productCount()))
                    .toList();
        }
        return supplierRepository.findAll().stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::supplierView).toList();
    }

    @Transactional(readOnly = true)
    public SupplierView supplierDetail(UUID id) {
        return supplierView(supplierRepository.findById(id).orElseThrow(() -> new NotFoundException("Supplier")));
    }

    @Cacheable(value = CACHE_PRODUCT_LIST, key = "'sup:' + #id + ':' + #page + ':' + #size + ':' + #lang + ':' + #sort "
            + "+ ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get()")
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
    // Cacheado por la combinación de filtros (TTL 60 s / Redis; invalidado al mutar productos). La clave
    // INCLUYE la moneda de display (keyGenerator) porque el precio mostrado depende de ella: sin eso, un
    // usuario en EUR vería el precio cacheado en la primera moneda solicitada (el margen/conversión "no se
    // reflejaría" por moneda).
    @Cacheable(value = CACHE_PRODUCT_LIST, keyGenerator = "currencyAwareKeyGenerator")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productListFull(int page, int size, String lang,
            ProductListFilters filters, String sort) {
        String q = filters.q();
        UUID categoryId = filters.categoryId();
        UUID supplierId = filters.supplierId();
        BigDecimal minPrice = filters.minPrice();
        BigDecimal maxPrice = filters.maxPrice();
        String shipFrom = filters.shipFrom();
        Boolean freeShipping = filters.freeShipping();
        Boolean selfPickup = filters.selfPickup();
        Boolean hasVideo = filters.hasVideo();
        Integer minRating = filters.minRating();
        Integer inventoryMin = filters.inventoryMin();
        String certification = filters.certification();
        Boolean verified = filters.verified();

        int safeSize = Math.min(size, 100);
        Sort sortSpec = sortFor(sort);
        Pageable pageable = PageRequest.of(page, safeSize, sortSpec);

        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String shipCc = shipFrom == null ? null : shipFrom.toUpperCase();
        BigDecimal minRatingBd = minRating == null ? null : BigDecimal.valueOf(minRating);
        boolean certFilter = certification != null && !certification.isBlank();
        boolean priceFilter = minPrice != null || maxPrice != null;
        // Filtro de verificación manual (solo lo envía el admin desde /admin/browse). Se aplica en memoria.
        boolean verifiedFilter = verified != null;

        // El filtro de precio y el de certificación se aplican en la capa de aplicación, NO en el SQL.
        // Motivo del precio: el número que ve el usuario (displayPrice) se obtiene de la variante
        // representativa → coste en USD → margen (reglas) → conversión a la moneda activa (X-Currency).
        // El SQL solo conoce base_price en CNY, así que filtrar ahí daría rangos sin sentido para EUR/USD/etc.
        // Por eso aquí filtramos sobre displayPrice, que está en la MISMA moneda que el usuario seleccionó
        // → el filtro de precio funciona para cualquier moneda. Se pagina en memoria para que el total y
        // las páginas sean correctos (el catálogo está acotado por el resto de filtros).
        if (priceFilter || certFilter || verifiedFilter) {
            String certUp = certFilter ? certification.toUpperCase() : null;
            Pageable scan = PageRequest.of(0, 5000, sortSpec);
            Page<ProductEntity> raw = productRepository.searchStorefront(ProductStatus.ACTIVE, needle, categoryId,
                    supplierId, null, null, shipCc, freeShipping, selfPickup, hasVideo, minRatingBd, inventoryMin, scan);
            List<ProductSummaryView> all = raw.getContent().stream()
                    .filter(p -> certUp == null || (p.getCertifications() != null && p.getCertifications().stream()
                            .anyMatch(c -> c != null && c.toUpperCase().contains(certUp))))
                    .filter(p -> !verifiedFilter || verified.equals(Boolean.TRUE.equals(p.getVerified())))
                    .map(p -> productMapper.toSummary(p, lang))
                    .filter(v -> withinPrice(v.displayPrice(), minPrice, maxPrice)).toList();
            int total = all.size();
            int from = Math.min(page * safeSize, total);
            int to = Math.min(from + safeSize, total);
            return PageResponse.from(new PageImpl<>(all.subList(from, to), pageable, total));
        }

        Page<ProductEntity> raw = productRepository.searchStorefront(ProductStatus.ACTIVE, needle, categoryId,
                supplierId, null, null, shipCc, freeShipping, selfPickup, hasVideo, minRatingBd, inventoryMin, pageable);
        List<ProductSummaryView> slice = raw.getContent().stream().map(p -> productMapper.toSummary(p, lang)).toList();
        return PageResponse.from(new PageImpl<>(slice, pageable, raw.getTotalElements()));
    }

    /**
     * Lista los productos FAVORITOS (por sus IDs, en el orden dado = del más reciente al más antiguo) con el
     * mismo pipeline de precios/formateo que el catálogo. Los inactivos se omiten. Pagina en memoria.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> favorites(List<UUID> productIds, int page, int size, String lang) {
        int safe = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, safe);
        if (productIds == null || productIds.isEmpty()) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }
        Map<UUID, ProductEntity> byId = productRepository.findAllById(productIds).stream()
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        List<ProductSummaryView> all = productIds.stream().map(byId::get).filter(Objects::nonNull)
                .map(p -> productMapper.toSummary(p, lang)).toList();
        // (long) para que un ?page enorme no desborde el int y deje un índice negativo que revienta
        // el subList con un 500.
        int from = (int) Math.min((long) page * safe, all.size());
        int to = Math.min(from + safe, all.size());
        return PageResponse.from(new PageImpl<>(all.subList(from, to), pageable, all.size()));
    }

    /** El precio ya viene en la moneda del usuario (displayPrice); rango inclusivo, excluye nulos si hay filtro. */
    private boolean withinPrice(BigDecimal price, BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            return true;
        }
        if (price == null) {
            return false;
        }
        return (min == null || price.compareTo(min) >= 0) && (max == null || price.compareTo(max) <= 0);
    }

    // @Transactional imprescindible: este método delega en productListFull() por self-invocation (misma
    // clase), y en la self-invocation NO se aplica el proxy @Transactional de productListFull. Sin la
    // transacción aquí, el mapeo a summary (que carga translations LAZY) falla con LazyInitializationException
    // "no session" (p.ej. GET /catalog/products/newest daba 500). Con esta anotación la sesión sigue abierta.
    @Cacheable(value = CACHE_PRODUCT_LIST, keyGenerator = "currencyAwareKeyGenerator")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productList(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return productListFull(page, size, lang,
                ProductListFilters.basic(q, categoryId, supplierId, minPrice, maxPrice), sort);
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
        // Indexed COUNT (idx product.category_id) instead of loading the whole product table per category.
        long count = productRepository.countByCategoryId(c.getId());
        return new CategoryView(c.getId(), c.getSlug(), translatedName(c, lang), c.getNameZh(),
                c.getParent() != null ? c.getParent().getId() : null, c.getPosition(), c.getIcon(), (int) count,
                children);
    }

    public SupplierView supplierView(SupplierEntity s) {
        // Indexed COUNT (idx product.supplier_id) instead of scanning the whole product table.
        long count = productRepository.countBySupplierId(s.getId());
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
