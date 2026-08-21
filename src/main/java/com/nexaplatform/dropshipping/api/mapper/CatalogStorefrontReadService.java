package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SupplierView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.VariantView;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedSupplier;
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
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Shared storefront catalog read-projection helper. Holds the category/supplier/
 * variant view logic and the SQL-backed product listing so that both
 * {@code StorefrontCatalogController} and {@code PartnerCatalogController} expose
 * identical shapes without one controller injecting the other (the partner→storefront
 * controller dependency is replaced by this shared collaborator + the CatalogUseCase).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogStorefrontReadService {

    private final ProductRepository productRepository;
    private final CustomsDeclarationGroupRepository declarationGroupRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierSearchService supplierSearchService;
    private final ProductVariantRepository variantRepository;
    private final ProductMapper productMapper;
    private final PricingService pricingService;
    private final PromotionService promotionService;
    private final ProductSearchService productSearchService;

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
                    + "+ T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() + ':' "
                    + "+ T(com.nexaplatform.dropshipping.application.service.PricingCountryHolder).get()")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsByCategory(String idOrSlug, int page, int size, String lang,
            String sort) {
        UUID categoryId = resolveCategory(idOrSlug).getId();
        return listing(page, size, lang, ProductListFilters.basic(null, categoryId, null, null, null), sort);
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
            + "+ ':' + T(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder).get() "
            + "+ ':' + T(com.nexaplatform.dropshipping.application.service.PricingCountryHolder).get()")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsBySupplier(UUID id, int page, int size, String lang, String sort) {
        return listing(page, size, lang, ProductListFilters.basic(null, null, id, null, null), sort);
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
        return listing(page, size, lang, filters, sort);
    }

    /**
     * El listado real. Va sin anotaciones a propósito: es el punto al que llaman los demás métodos de
     * esta misma clase. La autoinvocación NO pasa por el proxy de Spring, así que el {@code @Cacheable}
     * y el {@code @Transactional} del método invocado nunca se aplicaban; con el cuerpo aquí, esas
     * anotaciones quedan solo donde de verdad actúan: el método público por el que entra la petición.
     */
    private PageResponse<ProductSummaryView> listing(int page, int size, String lang, ProductListFilters filters,
            String sort) {
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
        // Acotar `page`: un offset gigante (page*size) desbordaba y caía en un 500 genérico. Con un techo
        // razonable devolvemos una página vacía en vez de reventar (el catálogo real nunca llega ahí).
        int safePage = Math.max(0, Math.min(page, 100_000));
        Sort sortSpec = sortFor(sort);
        Pageable pageable = PageRequest.of(safePage, safeSize, sortSpec);

        String needle = (q == null || q.isBlank()) ? null : Texts.escapeLikeWildcards(q.trim().toLowerCase());
        String shipCc = shipFrom == null ? null : shipFrom.toUpperCase();
        BigDecimal minRatingBd = minRating == null ? null : BigDecimal.valueOf(minRating);
        boolean certFilter = certification != null && !certification.isBlank();
        boolean priceFilter = minPrice != null || maxPrice != null;
        // Filtro de verificación manual (solo lo envía el admin desde /admin/browse). Se aplica en memoria.
        boolean verifiedFilter = verified != null;
        // «Ver los productos» de una promoción: lista solo los alcanzados por ella (por categoría o por
        // producto; una promoción global no filtra). Se resuelve una vez y se aplica en el mismo barrido
        // en memoria que el resto de filtros de la capa de aplicación.
        java.util.function.Predicate<ProductEntity> promoFilter =
                promotionService.reachFilter(filters.promotionId()).orElse(null);

        // Texto libre: el idioma activo acota el fuzzy a UN idioma (buscando "botas" en español, el fuzzy
        // contra los 8 idiomas casaba "botao" en portugués) y `ranked` ordena por relevancia — el término
        // en el título primero — solo cuando el usuario no ha pedido otro orden explícito.
        final String langCode = (lang == null || lang.isBlank()) ? "es" : lang.toLowerCase();
        final boolean ranked = needle != null && (sort == null || sort.isBlank() || "best_match".equals(sort));
        // `wide` = mirar también dentro de las descripciones largas. Se deja para el segundo intento: una
        // falda cuya descripción dice "combina con botas" no es un resultado de "botas", pero sí es mejor
        // que devolver la página vacía cuando NADA casa por título/atributo/variante.
        BiFunction<Boolean, Pageable, Page<ProductEntity>> search = (wide, pg) -> productRepository
                .searchStorefront(ProductStatus.ACTIVE, needle, categoryId, supplierId, null, null, shipCc,
                        freeShipping, selfPickup, hasVideo, minRatingBd, inventoryMin,
                        verifiedFilter ? verified : null, langCode, wide, ranked, pg);

        // Los filtros que NO se pueden delegar: promoción, certificación, verificado y precio. Este último
        // porque el número que ve el usuario (displayPrice) sale de la variante representativa → coste en
        // USD → margen (reglas) → conversión a la moneda activa (X-Currency), mientras que el SQL solo
        // conoce base_price en CNY: filtrar ahí daría rangos sin sentido para EUR/USD/etc.
        String certUp = certFilter ? certification.toUpperCase() : null;
        // `verified` YA NO va aquí: lo resuelve el SQL. Filtrarlo en memoria obligaba a pasar por el
        // camino del `scan` de 5.000 filas, que con un catálogo mayor deja fuera al resto — el filtro
        // devolvía 0 resultados sin que nada fallara.
        InMemoryFilters postFilters = new InMemoryFilters(promoFilter, certUp, false, null, minPrice,
                maxPrice);

        // TEXTO LIBRE → OpenSearch, que es el motor principal de la búsqueda: entiende la morfología de los
        // 8 idiomas (plurales, acentos, chino) y devuelve los productos ORDENADOS POR RELEVANCIA. Aquí solo
        // llegan identificadores; la visibilidad, los filtros y el precio los sigue resolviendo la BD.
        // «Ver los que no suman arancel»: el grupo se resuelve a los productos de su TERNA y se entra por el
        // mismo camino que los resultados del buscador. Nulo = sin filtro; vacío = el grupo no existe o no
        // tiene productos, y entonces la respuesta es una página vacía, nunca el catálogo entero.
        List<UUID> delGrupo = idsDeLaTernaDe(filters.dutyGroupId());
        if (delGrupo != null && delGrupo.isEmpty()) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }

        if (needle != null) {
            Optional<List<UUID>> relevant = productSearchService.searchRelevantIds(needle, langCode);
            if (relevant.isPresent()) {
                List<UUID> ids = delGrupo == null ? relevant.get()
                        : relevant.get().stream().filter(delGrupo::contains).toList();
                return fromRelevantIds(ids, categoryId, supplierId, shipCc, freeShipping, selfPickup,
                        hasVideo, minRatingBd, inventoryMin, sort, lang, postFilters, safePage, safeSize, pageable);
            }
            // Si el buscador no ha podido responder (caído, índice aún sin construir) se sigue por SQL: la
            // búsqueda se degrada, pero el catálogo NUNCA deja de funcionar.
            log.debug("Búsqueda '{}' resuelta por SQL — OpenSearch no disponible", needle);
        }

        // Con filtro de grupo manda el grupo. Si además había texto y el buscador estaba caído, el texto se
        // pierde: enseñar productos de FUERA del grupo sería prometer «no suma arancel» de mercancía que sí
        // lo suma, y esa diferencia la pondría el comercio al despachar. Devolver de más dentro del grupo es
        // ruido; devolver de fuera es una promesa falsa.
        if (delGrupo != null) {
            if (needle != null) {
                log.debug("Filtro por grupo con texto '{}' sin buscador: se ignora el texto", needle);
            }
            return fromRelevantIds(delGrupo, categoryId, supplierId, shipCc, freeShipping, selfPickup, hasVideo,
                    minRatingBd, inventoryMin, sort, lang, postFilters, safePage, safeSize, pageable);
        }

        if (priceFilter || certFilter || promoFilter != null) {
            Pageable scan = PageRequest.of(0, 5000, sortSpec);
            Page<ProductEntity> raw = search.apply(false, scan);
            if (needle != null && raw.isEmpty()) {
                raw = search.apply(true, scan);
            }
            return paginate(applyPostFilters(raw.getContent(), postFilters, lang), safePage, safeSize, pageable);
        }

        Page<ProductEntity> raw = search.apply(false, pageable);
        if (needle != null && raw.getTotalElements() == 0) {
            raw = search.apply(true, pageable);
        }
        List<ProductSummaryView> slice = raw.getContent().stream().map(p -> productMapper.toSummary(p, lang)).toList();
        return PageResponse.from(new PageImpl<>(slice, pageable, raw.getTotalElements()));
    }

    /**
     * Los productos que comparten terna con ese grupo de declaración, o {@code null} si no hay filtro.
     *
     * <p>Se filtra por la <b>terna</b> (partida, material y uso) y no por una columna en el producto: es la
     * terna la que hace que dos productos se declaren con la misma descripción y la aduana los cuente como
     * una sola línea. Guardar el grupo en cada producto obligaría a reescribir miles de filas cada vez que
     * se aprueba o se retira una descripción.
     */
    private List<UUID> idsDeLaTernaDe(UUID dutyGroupId) {
        if (dutyGroupId == null) {
            return null;
        }
        return declarationGroupRepository.findById(dutyGroupId)
                .map(g -> productRepository.idsForCustomsTerna(ProductStatus.ACTIVE, g.getHs6(), g.getMaterial(),
                        g.getUsageCode()))
                .orElseGet(List::of);
    }

    /**
     * Materializa en productos del escaparate los identificadores que ha devuelto el buscador, conservando
     * su orden de relevancia salvo que el usuario haya pedido otro criterio (precio, novedad, ventas…).
     *
     * <p>Todo el pipeline va en memoria a partir de aquí, y puede: la lista viene acotada por el buscador
     * ({@link ProductSearchService#MAX_IDS}), mientras que el barrido SQL equivalente traía hasta 5.000.
     */
    @SuppressWarnings("java:S107")
    private PageResponse<ProductSummaryView> fromRelevantIds(List<UUID> ids, UUID categoryId, UUID supplierId,
            String shipCc, Boolean freeShipping, Boolean selfPickup, Boolean hasVideo, BigDecimal minRatingBd,
            Integer inventoryMin, String sort, String lang, InMemoryFilters postFilters, int safePage, int safeSize,
            Pageable pageable) {
        if (ids.isEmpty()) {
            return PageResponse.from(new PageImpl<>(List.of(), pageable, 0));
        }
        List<ProductEntity> found = productRepository.searchStorefrontByIds(ProductStatus.ACTIVE, ids, categoryId,
                supplierId, shipCc, freeShipping, selfPickup, hasVideo, minRatingBd, inventoryMin);
        List<ProductEntity> ordered = orderBy(found, ids, sort);
        return paginate(byDisplayPrice(applyPostFilters(ordered, postFilters, lang), sort), safePage, safeSize,
                pageable);
    }

    /**
     * Ordena por el precio que el usuario VE, no por el coste en CNY.
     *
     * <p>Pedir "precio más bajo" y recibir 5,53 → 7,87 → 6,50 es lo que pasa al ordenar por {@code
     * basePrice}: entre el coste y el precio mostrado median el margen (que varía por producto — reglas de
     * canal, ajuste por MOQ) y la conversión a la divisa activa, así que el orden del coste no es el orden
     * del precio. Aquí ya están los precios calculados, de modo que se ordena por el número real de la
     * ficha. Solo aplica a la búsqueda: el listado por SQL sigue ordenando en base de datos.
     */
    private static List<ProductSummaryView> byDisplayPrice(List<ProductSummaryView> views, String sort) {
        if (!"price_asc".equals(sort) && !"price_desc".equals(sort)) {
            return views;
        }
        Comparator<ProductSummaryView> byPrice = Comparator.comparing(ProductSummaryView::displayPrice,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return views.stream().sorted("price_desc".equals(sort) ? byPrice.reversed() : byPrice).toList();
    }

    /**
     * Orden final de una búsqueda. Por defecto manda la RELEVANCIA (la posición que le dio el buscador);
     * si el usuario ha elegido otro criterio en el desplegable, ese gana — buscar y pedir "precio más
     * bajo" tiene que ordenar por precio, no por relevancia.
     */
    private static List<ProductEntity> orderBy(List<ProductEntity> found, List<UUID> relevanceOrder, String sort) {
        Comparator<ProductEntity> explicit = switch (sort == null ? "" : sort) {
            case "price_asc" -> Comparator.comparing(ProductEntity::getBasePrice, nullsLast());
            case "price_desc" -> Comparator.comparing(ProductEntity::getBasePrice, nullsLast()).reversed();
            case "newest" -> Comparator.comparing(ProductEntity::getCreatedAt, nullsLast()).reversed();
            case "sales", "lists" -> Comparator.comparing(ProductEntity::getMonthlySales, nullsLast()).reversed();
            case "rating" -> Comparator.comparing(ProductEntity::getRating, nullsLast()).reversed();
            case "inventory" -> Comparator.comparing(ProductEntity::getInventoryCount, nullsLast()).reversed();
            default -> null;
        };
        if (explicit != null) {
            return found.stream().sorted(explicit).toList();
        }
        Map<UUID, Integer> rank = new HashMap<>();
        for (int i = 0; i < relevanceOrder.size(); i++) {
            rank.put(relevanceOrder.get(i), i);
        }
        return found.stream().sorted(Comparator.comparingInt(p -> rank.getOrDefault(p.getId(), Integer.MAX_VALUE)))
                .toList();
    }

    /** Comparador natural que deja los nulos al final — hay productos sin precio, sin nota o sin ventas. */
    private static <T extends Comparable<T>> Comparator<T> nullsLast() {
        return Comparator.nullsLast(Comparator.naturalOrder());
    }

    /** Filtros que solo se pueden resolver con el producto ya mapeado (precio en divisa) o en memoria. */
    private record InMemoryFilters(java.util.function.Predicate<ProductEntity> promo, String certification,
            boolean verifiedFilter, Boolean verified, BigDecimal minPrice, BigDecimal maxPrice) {
    }

    private List<ProductSummaryView> applyPostFilters(List<ProductEntity> entities, InMemoryFilters f, String lang) {
        return entities.stream()
                .filter(p -> f.promo() == null || f.promo().test(p))
                .filter(p -> f.certification() == null || (p.getCertifications() != null && p.getCertifications()
                        .stream().anyMatch(c -> c != null && c.toUpperCase().contains(f.certification()))))
                .filter(p -> !f.verifiedFilter() || f.verified().equals(Boolean.TRUE.equals(p.getVerified())))
                .map(p -> productMapper.toSummary(p, lang))
                .filter(v -> withinPrice(v.displayPrice(), f.minPrice(), f.maxPrice())).toList();
    }

    private static PageResponse<ProductSummaryView> paginate(List<ProductSummaryView> all, int safePage, int safeSize,
            Pageable pageable) {
        int total = all.size();
        // (long) para que un ?page enorme no desborde el int: el índice salía negativo y el subList
        // respondía 500 en vez de una página vacía.
        int from = (int) Math.min((long) safePage * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return PageResponse.from(new PageImpl<>(all.subList(from, to), pageable, total));
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

    // @Transactional imprescindible: sin la transacción aquí, el mapeo a summary (que carga translations
    // LAZY) falla con LazyInitializationException "no session" (p.ej. GET /catalog/products/newest daba
    // 500). Con esta anotación la sesión sigue abierta mientras se construye la página.
    //
    // Versión con los filtros sueltos de {@link #productListFull}, que es la que los agrupa en
    // ProductListFilters. Sobrevive porque la usan llamadores (controlador del escaparate y sus tests)
    // que se migrarán aparte; de ahí que se silencie S107 en vez de partir la firma por la mitad.
    @SuppressWarnings("java:S107")
    @Cacheable(value = CACHE_PRODUCT_LIST, keyGenerator = "currencyAwareKeyGenerator")
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productList(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return listing(page, size, lang, ProductListFilters.basic(q, categoryId, supplierId, minPrice, maxPrice),
                sort);
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
        // Precio de VENTA, nunca v.getPrice(): esa columna es el coste CNY del proveedor, y servirla
        // aquí regalaba el margen a cualquier usuario logueado (y a los partners).
        BigDecimal retail = pricingService.priceFor(v.getProduct(), v).displayAmount();
        return new VariantView(v.getId(), v.getSku(), v.getExternalId(), v.getTitle(), retail, v.getStock(), img,
                v.getOptions() != null ? v.getOptions() : Map.of(), v.isActive());
    }

    public ProductSummaryView summaryFor(ProductEntity p, String lang) {
        return productMapper.toSummary(p, lang);
    }

    public Sort sortFor(String sort) {
        Sort criterio = switch (sort == null ? "best_match" : sort) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "basePrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "sales", "lists" -> Sort.by(Sort.Direction.DESC, "monthlySales");
            case "rating" -> Sort.by(Sort.Direction.DESC, "rating");
            case "inventory" -> Sort.by(Sort.Direction.DESC, "inventoryCount");
            default -> Sort.by(Sort.Direction.DESC, "trendScore");
        };
        return criterio.and(DESEMPATE);
    }

    /**
     * Desempate final de TODA ordenación paginada. No es un adorno: sin él, la paginación del catálogo
     * está rota.
     *
     * <p>Los siete criterios de arriba ordenan por campos donde el catálogo empata en masa —5.181 de 5.485
     * productos tienen {@code trendScore = 0}, y otro tanto pasa con las ventas mensuales o el inventario—.
     * Cuando miles de filas empatan y no hay más criterio, PostgreSQL las devuelve en el orden que le
     * resulte más barato, y ese orden NO es estable entre consultas: basta un UPDATE en cualquier producto
     * para que cambie. Medido sobre el catálogo real: la misma página (LIMIT 12 OFFSET 2400) pedida dos
     * veces con un solo UPDATE en medio devolvió 12 productos DISTINTOS, cero coincidencias.
     *
     * <p>El efecto que ve el comprador es que, al bajar por el catálogo, unos productos le salen repetidos
     * y otros no le salen NUNCA — quedan inalcanzables por más que siga bajando.
     */
    private static final Sort DESEMPATE = Sort.by(Sort.Direction.ASC, "id");

    /**
     * Nombre de la categoría en el idioma pedido, con el chino como respaldo.
     *
     * <p>El idioma se comprueba aquí: hoy no llega nulo porque el parámetro de la petición tiene valor
     * por defecto, pero eso es una casualidad de una capa que este método no controla.
     */
    public static String translatedName(CategoryEntity c, String lang) {
        if (lang == null || c.getTranslations() == null) {
            return c.getNameZh();
        }
        return c.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage()))
                .map(CategoryTranslationEntity::getName).findFirst().orElse(c.getNameZh());
    }
}
