package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.LivePromotionView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.AttributeKeyView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.AttributeView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteItemIn;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteLineOut;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteOut;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HistoryPoint;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HomeSection;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HomeSectionsResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImageSearchRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImageSearchResult;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImportUrlRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImportUrlResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.MarginEstimate;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingQuoteItem;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingQuoteRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingRateView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingZoneView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SpecificationView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SuggestionView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SupplierView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.VariantView;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.application.service.CatalogDutyBadgeService;
import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.ParcelAggregator;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.application.service.ProductDetailQueryService;
import com.nexaplatform.dropshipping.application.service.PromotionShowcaseService;
import com.nexaplatform.dropshipping.application.service.WelcomeExamplesService;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;

/**
 * Public catalog API. Mirrors what an integrator needs to list, search and inspect every
 * piece of a 1688-style product — categories, products, variants, specs, attributes, tags
 * and shipping coverage — without ever touching admin endpoints.
 *
 * <p>Priced product reads (summary/detail/bestsellers) come from the shared
 * {@link CatalogUseCase}; the category/supplier/variant view projections and the
 * SQL-backed listing come from the shared {@link CatalogStorefrontReadService}
 * (also used by the partner controller). The endpoints that stay here are the
 * storefront-only read shapes (specs, attributes, tags, shipping quote, suggest,
 * home sections, import-url, image-search, history, margin estimate).
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class StorefrontCatalogController implements StorefrontCatalogApi {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String NEWEST = "newest";

    private final CatalogUseCase catalogUseCase;
    private final CatalogDutyBadgeService dutyBadges;
    private final CustomsValuationService customsValuation;
    private final CatalogStorefrontReadService storefrontRead;
    private final ProductDetailQueryService productDetailQuery;
    private final ProductRepository productRepository;
    private final ProductSpecificationRepository specRepository;
    private final ProductAttributeRepository attributeRepository;
    private final ProductTagRepository tagRepository;
    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ProductHistoryRepository historyRepository;
    private final ProductMapper productMapper;
    // DROP-669/678: estimación de rentabilidad con datos reales (tramo aplicable + margen configurado).
    private final PricingService pricingService;
    private final PromotionShowcaseService promotionShowcase;
    private final MarginService marginService;
    private final CurrencyRateService currencyService;
    private final ProductPriceTierRepository priceTierRepository;
    private final WelcomeExamplesService welcomeExamples;
    private final CountryTaxService countryTaxService;
    private final CheckoutPreviewService checkoutPreview;
    /** La cuenta del pedido: el carrito cotiza con la MISMA aritmética con la que se cobra. */
    private final OrderAmounts orderAmounts;
    /** Comisión de plataforma (%). DROP-680: por defecto 0 — no se inventa una comisión. */
    @Value("${nexadrop.platform.commission-pct:0}")
    private BigDecimal platformCommissionPct;

    /* =========================== VIEW RECORDS =========================== */

    /* =========================== CATEGORIES (shared read) =========================== */

    @Override
    public List<CategoryView> categoriesFlat(String lang) {
        return storefrontRead.categoriesFlat(lang);
    }

    @Override
    public List<CategoryView> categoriesTree(String lang) {
        return storefrontRead.categoriesTree(lang);
    }

    @Override
    public CategoryView categoryDetail(String idOrSlug, String lang) {
        return storefrontRead.categoryDetail(idOrSlug, lang);
    }

    @Override
    public List<CategoryView> categoryChildren(String idOrSlug, String lang) {
        return storefrontRead.categoryChildren(idOrSlug, lang);
    }

    @Override
    public List<CategoryBreadcrumb> categoryBreadcrumb(String idOrSlug, String lang) {
        return storefrontRead.categoryBreadcrumb(idOrSlug, lang);
    }

    @Override
    public PageResponse<ProductSummaryView> productsByCategory(String idOrSlug, int page, int size, String lang,
            String sort) {
        return storefrontRead.productsByCategory(idOrSlug, page, size, lang, sort);
    }

    /* =========================== SUPPLIERS (shared read) =========================== */

    @Override
    public List<SupplierView> suppliers() {
        return storefrontRead.suppliers();
    }

    @Override
    public SupplierView supplierDetail(UUID id) {
        return storefrontRead.supplierDetail(id);
    }

    @Override
    public PageResponse<ProductSummaryView> productsBySupplier(UUID id, int page, int size, String lang, String sort) {
        return storefrontRead.productsBySupplier(id, page, size, lang, sort);
    }

    /* =========================== PRODUCTS =========================== */

    @Override
    @SuppressWarnings("java:S107")
    public PageResponse<ProductSummaryView> list(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String shipFrom, Boolean freeShipping,
            Boolean selfPickup, Boolean hasVideo, Integer minRating, Integer inventoryMin, String certification,
            String sort, Boolean verified, UUID promotionId, UUID dutyGroupId, Boolean dutyGroupsFromCart,
            List<UUID> cartProductIds, Integer seed) {
        // El filtro de verificación es SOLO para admin: si el que consulta no es admin, se ignora.
        Boolean verifiedFilter = SecurityUtils.isAdmin() ? verified : null;
        PageResponse<ProductSummaryView> pagina = storefrontRead.productListFull(page, size, lang,
                new ProductListFilters(q, categoryId, supplierId, minPrice, maxPrice, shipFrom, freeShipping,
                        selfPickup, hasVideo, minRating, inventoryMin, certification, verifiedFilter, promotionId,
                        gruposDelFiltro(dutyGroupId, dutyGroupsFromCart, cartProductIds)),
                sort, seed);
        return conElArancel(pagina, cartProductIds);
    }

    /**
     * Por qué grupos de declaración se filtra: por el de un producto concreto, o por los del carrito entero.
     *
     * <p>El carrito tiene tantas líneas de declaración como ternas distintas lleve. Con tres productos de
     * tres grupos se pagan tres derechos, y «lo que no suma arancel» es lo que encaje en <b>cualquiera</b>
     * de los tres: filtrar por uno solo deja fuera dos tercios del catálogo que tampoco costaría nada.
     *
     * <p>Manda el producto cuando se ha elegido uno —es una petición explícita del comprador sobre ESE
     * artículo, y funciona con el carrito vacío—; el carrito es el respaldo.
     *
     * <p>Cada línea lleva su ORIGEN cuando viene del carrito: lo que separa una línea de declaración de
     * otra es clasificación + descripción + <b>origen</b>, así que sin él el filtro devolvía productos del
     * mismo grupo pero de otro país, que suman los 3 EUR igualmente.
     *
     * @return {@code null} si no hay filtro; lista vacía si se pidió el del carrito y en él no hay ni un
     *         grupo aprobado, porque entonces cualquier producto abre línea nueva y no encaja ninguno
     */
    private List<ProductListFilters.DutyLine> gruposDelFiltro(UUID dutyGroupId, Boolean dutyGroupsFromCart,
            List<UUID> cartProductIds) {
        // Donde no se cobra derecho por artículo no hay nada que agrupar, así que el filtro se ignora: el
        // régimen de 3 EUR es de los 27 de la Unión y en el resto del mundo esta pantalla no habla de
        // aranceles. Sin esto, cambiar de país con el filtro puesto —o abrir un enlace compartido desde
        // fuera de la UE— dejaba el catálogo recortado por una promesa que allí no significa nada.
        if (customsValuation.perArticleFeeUsdCents(PricingCountryHolder.get()) <= 0) {
            return null;
        }
        if (dutyGroupId != null) {
            // Desde una tarjeta con el carrito vacío no hay con qué comparar el origen, así que se dejan
            // todos los del grupo: es «los de la misma familia», no una promesa sobre un carrito.
            return List.of(new ProductListFilters.DutyLine(dutyGroupId, null));
        }
        if (!Boolean.TRUE.equals(dutyGroupsFromCart)) {
            return null;
        }
        return dutyBadges.lineasDe(cartProductIds).stream()
                .map(l -> new ProductListFilters.DutyLine(l.grupoId(), l.originCountry())).toList();
    }

    /**
     * Añade a cada producto cuánto sube el arancel del carrito por llevárselo.
     *
     * <p>Va <b>fuera</b> del listado y no dentro, aunque dentro sería más cómodo: {@code productListFull}
     * está cacheado y su clave incluye los argumentos del método, así que meter el carrito ahí crearía una
     * entrada de caché por cada combinación de carrito —que no tiene fin— y echaría del hueco a las páginas
     * que de verdad se repiten. El arancel se calcula aparte, con la página ya resuelta.
     */
    private PageResponse<ProductSummaryView> conElArancel(PageResponse<ProductSummaryView> pagina,
            List<UUID> cartProductIds) {
        if (pagina == null || pagina.items() == null || pagina.items().isEmpty()) {
            return pagina;
        }
        Map<UUID, CatalogDutyBadgeService.DutyBadge> badges = dutyBadges.badgesFor(cartProductIds,
                pagina.items().stream().map(ProductSummaryView::id).toList(), PricingCountryHolder.get());
        if (badges.isEmpty()) {
            return pagina;
        }
        List<ProductSummaryView> conArancel = pagina.items().stream().map(v -> {
            CatalogDutyBadgeService.DutyBadge badge = badges.get(v.id());
            return badge == null
                    ? v
                    : v.withDuty(badge.extraDutyCents(), badge.extraDutyFormatted(), badge.dutyGroupId(),
                            badge.dutyCovered());
        }).toList();
        return new PageResponse<>(conArancel, pagina.page(), pagina.size(), pagina.totalElements(),
                pagina.totalPages());
    }

    @Override
    public ProductDetailView detailBySlug(String slug, String lang, List<UUID> cartProductIds) {
        return paraQuienPregunta(conElArancel(catalogUseCase.getProductBySlug(slug, lang), cartProductIds));
    }

    @Override
    public ProductDetailView detailById(UUID id, String lang, List<UUID> cartProductIds) {
        return paraQuienPregunta(conElArancel(catalogUseCase.getProductById(id, lang), cartProductIds));
    }

    /**
     * Quita de la ficha lo que solo le incumbe a quien administra, salvo que quien pregunta lo sea.
     *
     * <p>Esta es la MISMA ficha que pinta el panel de administración dentro de la propia página de
     * producto —el bloque de origen, el desglose en yuanes—, y por eso los datos internos viajan en
     * ella. Lo que faltaba era la puerta: sin sesión de administración, la respuesta llevaba igualmente
     * el enlace a la oferta de origen en 1688, su identificador y el proveedor. Medido contra
     * producción el 8-sep-2026: seis de seis fichas. La interfaz los ocultaba; el JSON no.
     *
     * <p>Va AQUÍ, en el controlador, y no dentro del caso de uso: la ficha se sirve cacheada y la caché
     * no puede depender de quién pregunta —una entrada guardada para el administrador se le serviría al
     * siguiente visitante—. Se decora fuera, igual que el arancel, y por el mismo motivo.
     */
    private ProductDetailView paraQuienPregunta(ProductDetailView ficha) {
        if (ficha == null || SecurityUtils.isAdmin()) {
            return ficha;
        }
        // Quien revisa las fotos SÍ ve el origen —lo necesita para cotejar la galería contra la oferta
        // del proveedor— y NO ve los importes. Es un recorte intermedio, no «medio administrador»: el
        // desglose en yuanes, el coste y el margen se van igual que para cualquier visitante.
        if (SecurityUtils.isReviewer()) {
            return ficha.sinImportesInternos();
        }
        return ficha.sinDatosInternos();
    }

    /**
     * La ficha promete lo mismo que el listado porque lo calcula el mismo servicio: si la tarjeta dijera
     * «sin arancel adicional» y la ficha otra cosa, una de las dos estaría mintiendo.
     */
    private ProductDetailView conElArancel(ProductDetailView ficha, List<UUID> cartProductIds) {
        if (ficha == null) {
            return null;
        }
        CatalogDutyBadgeService.DutyBadge badge = dutyBadges
                .badgesFor(cartProductIds, List.of(ficha.id()), PricingCountryHolder.get()).get(ficha.id());
        return badge == null
                ? ficha
                : ficha.withDuty(badge.extraDutyCents(), badge.extraDutyFormatted(), badge.dutyGroupId(),
                        badge.dutyCovered());
    }

    @Override
    public ProductDetailView detailByExternal(String source, String externalId, String lang) {
        return catalogUseCase.getProductByExternal(source, externalId, lang);
    }

    @Override
    public PageResponse<ProductSummaryView> bestsellers(UUID categoryId, int page, int size, String lang) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.from(catalogUseCase.listBestsellers(categoryId, pageable, lang));
    }

    @Override
    public PageResponse<ProductSummaryView> trending(UUID categoryId, int page, int size, String lang) {
        return bestsellers(categoryId, page, size, lang);
    }

    @Override
    public PageResponse<ProductSummaryView> newest(int page, int size, String lang) {
        return storefrontRead.productList(page, size, lang, null, null, null, null, null, NEWEST);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSummaryView> relatedProducts(UUID id, String lang, int limit) {
        return productDetailQuery.relatedProducts(id, limit).stream().map(x -> productMapper.toSummary(x, lang))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecificationView> specifications(UUID id, String lang) {
        return productDetailQuery.specifications(id, lang).stream()
                .map(s -> new SpecificationView(s.getSpecKey(), s.getSpecValue(), s.getPosition())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttributeView> attributes(UUID id, String lang) {
        return productDetailQuery.attributes(id, lang).entrySet().stream()
                .map(e -> new AttributeView(e.getKey(), e.getValue())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> tags(UUID id) {
        return productDetailQuery.tags(id);
    }

    @Override
    public List<CatalogImageDtoOut> images(UUID id) {
        return catalogUseCase.listProductImages(id);
    }

    @Override
    public List<CatalogPriceTierDtoOut> priceTiers(UUID id) {
        return catalogUseCase.listProductPriceTiers(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuggestionView> suggest(String q, String lang, int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.isEmpty())
            return List.of();
        return productRepository.findVisibleByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 200)).getContent().stream()
                .filter(p -> matchesNeedle(p, needle)).limit(limit).map(p -> new SuggestionView("product",
                        firstNonNull(translatedTitle(p, lang), p.getTitleZh()), p.getSlug()))
                .toList();
    }

    /* =========================== VARIANTS (shared read) =========================== */

    @Override
    public List<VariantView> variantsForProduct(UUID id) {
        return storefrontRead.variantsForProduct(id);
    }

    @Override
    public VariantView variantById(UUID id) {
        return storefrontRead.variantById(id);
    }

    @Override
    public VariantView variantBySku(UUID productId, String sku) {
        return storefrontRead.variantBySku(productId, sku);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantView> variantMatch(UUID productId, Map<String, String> options) {
        // Storefront-only: option-tuple match — delegated row-by-row to the shared variant view.
        return storefrontRead.variantsForProduct(productId).stream().filter(v -> options.entrySet().stream()
                .allMatch(e -> e.getValue().equalsIgnoreCase(v.options().get(e.getKey())))).toList();
    }

    /* =========================== ATTRIBUTES (taxonomy) =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<AttributeKeyView> attributeKeys() {
        Map<String, Long> agg = attributeRepository.findAll().stream()
                .collect(Collectors.groupingBy(ProductAttributeEntity::getAttrKey, Collectors.counting()));
        return agg.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new AttributeKeyView(e.getKey(), e.getValue())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> attributeValues(String key) {
        return attributeRepository.findDistinctValuesByKey(key);
    }

    /* =========================== TAGS =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<String> allTags() {
        return tagRepository.findDistinctTags();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSummaryView> productsByTag(String tag, String lang, int limit) {
        List<UUID> ids = tagRepository.findProductIdsByTag(tag);
        return productRepository.findAllById(ids).stream().filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .limit(limit).map(p -> productMapper.toSummary(p, lang)).toList();
    }

    /* =========================== SHIPPING =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<ShippingZoneView> shippingZones(UUID supplierId) {
        return zoneRepository.findBySupplier_IdAndActiveTrueOrderByCountryCodeAsc(supplierId).stream()
                .map(z -> new ShippingZoneView(z.getSupplier().getId(), z.getSupplier().getName(), z.getCountryCode(),
                        z.getRegion(), z.isActive()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingRateView> shippingRates(UUID supplierId, String country) {
        return rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(supplierId, country.toUpperCase()).stream()
                .map(r -> new ShippingRateView(r.getId(), r.getSupplier().getId(), r.getCountryCode(), r.getMethod(),
                        r.getCarrier(), r.getTransitDaysMin(), r.getTransitDaysMax(),
                        BigDecimal.valueOf(r.getBaseCents()).movePointLeft(2),
                        BigDecimal.valueOf(r.getPerKgCents()).movePointLeft(2), r.getMaxWeightGrams()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingQuoteItem> shippingQuote(ShippingQuoteRequest req) {
        ProductEntity p = productRepository.findById(req.productId())
                .orElseThrow(() -> new NotFoundException("Product"));
        SupplierEntity s = p.getSupplier();
        if (s == null)
            throw new NotFoundException("Supplier");
        int unitGrams = effectiveWeight(p);
        int totalGrams = unitGrams * Math.max(1, req.quantity());

        List<ShippingRateEntity> rates = rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(),
                req.country().toUpperCase());
        return rates.stream().filter(r -> r.getMaxWeightGrams() == null || totalGrams <= r.getMaxWeightGrams())
                .filter(r -> r.getMinWeightGrams() == null || totalGrams >= r.getMinWeightGrams()).map(r -> {
                    double kg = totalGrams / 1000.0;
                    long costCents = r.getBaseCents() + Math.round(r.getPerKgCents() * kg);
                    BigDecimal cost = BigDecimal.valueOf(costCents).movePointLeft(2);
                    return new ShippingQuoteItem(s.getId(), r.getMethod(), r.getCarrier(), r.getTransitDaysMin(),
                            r.getTransitDaysMax(), cost, "USD");
                }).sorted(Comparator.comparing(ShippingQuoteItem::cost)).toList();
    }

    /* =========================== CART QUOTE (precio actual = el que se cobra) =========================== */

    /**
     * Cotiza el carrito con el precio ACTUAL de cada producto (margen + tasa del día) en la moneda activa
     * — EXACTAMENTE lo que se factura y se cobra. El carrito del cliente "congela" el precio al añadir,
     * así que el checkout debe re-cotizar aquí para que lo mostrado coincida con lo cobrado (evita "veo X
     * y me cobran Y" cuando el precio cambió tras añadir al carrito).
     *
     * <p>El subtotal es la suma de los importes de LÍNEA, cada uno redondeado una sola vez. Sumar
     * unitarios redondeados es lo que hacía que el carrito anunciara —y la pasarela liquidara— hasta un
     * 1,45 % de más.
     */
    @PostMapping("/cart-quote")
    @Transactional(readOnly = true)
    public CartQuoteOut cartQuote(@RequestBody List<CartQuoteItemIn> items) {
        String displayCode = pricingService.displayCurrencyCode();
        List<CartQuoteLineOut> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        int pesoTotal = 0;
        boolean faltaAlgunPeso = false;
        // Unidades de cada producto en TODA la cesta, para resolver el tramo por cantidad. Se cuenta por
        // producto y no por línea, igual que el pedido mínimo: el lote se compone mezclando variantes y
        // el proveedor solo mira cuántas unidades van en total.
        Map<UUID, Integer> unidadesPorProducto = new HashMap<>();
        for (CartQuoteItemIn it : items == null ? List.<CartQuoteItemIn>of() : items) {
            if (it != null && it.productId() != null) {
                unidadesPorProducto.merge(it.productId(), Math.max(1, it.quantity()), Integer::sum);
            }
        }
        // Las escaleras de todos los productos de golpe: una consulta en vez de una por línea.
        Map<UUID, List<ProductPriceTierEntity>> escaleras = unidadesPorProducto.isEmpty()
                ? Map.of()
                : priceTierRepository.findByProductIdInOrderByMinQtyAsc(unidadesPorProducto.keySet()).stream()
                        .collect(Collectors.groupingBy(x -> x.getProduct().getId()));
        for (CartQuoteItemIn it : items == null ? List.<CartQuoteItemIn>of() : items) {
            CartQuoteLineOut line = quoteLine(it, displayCode, unidadesPorProducto, escaleras);
            if (line != null) {
                lines.add(line);
                subtotal = subtotal.add(line.lineTotal());
                if (line.weightGrams() == null) {
                    faltaAlgunPeso = true;
                } else {
                    pesoTotal = Math.addExact(pesoTotal,
                            Math.multiplyExact(line.weightGrams(), Math.max(1, it.quantity())));
                }
            }
        }
        return new CartQuoteOut(displayCode, pricingService.displayCurrencySymbol(), lines, subtotal,
                currencyService.formatDisplay(subtotal, displayCode), pesoTotal, faltaAlgunPeso);
    }

    /**
     * Peso NETO de lo que se lleva el cliente, en gramos: el de la variante y, si no lo declara, el de la
     * ficha. {@code null} cuando no hay ninguno de los dos.
     *
     * <p>Es el peso del artículo, no el que factura el transportista —ese incluye el embalaje y el
     * volumétrico— porque lo que el comprador quiere saber es cuánto pesa lo que compra. Y jamás el
     * respaldo de 500 g que usa el cálculo del flete: sirve para poder cotizar, no para enseñárselo a
     * nadie como si fuera un dato medido. Un cero cuenta como ausente: un artículo no pesa cero gramos.
     */
    private static Integer pesoNetoDe(ProductEntity p, ProductVariantEntity v) {
        if (v != null && v.getWeightGrams() != null && v.getWeightGrams() > 0) {
            return v.getWeightGrams();
        }
        return p.getWeightGrams() != null && p.getWeightGrams() > 0 ? p.getWeightGrams() : null;
    }

    /**
     * Línea cotizada al precio actual, o {@code null} si no es cotizable (línea vacía, producto que ya no
     * existe o sin precio). Esas líneas se descartan en silencio a propósito: el carrito guardado en el
     * navegador puede arrastrar productos retirados del catálogo y no debe tumbar la cotización entera.
     */
    private CartQuoteLineOut quoteLine(CartQuoteItemIn it, String displayCode, Map<UUID, Integer> unidadesPorProducto,
            Map<UUID, List<ProductPriceTierEntity>> escaleras) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null
                ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        // Con el tramo que corresponde a las unidades de este producto: la cesta tiene que anunciar el
        // mismo precio que se va a cobrar, y hasta ahora tarificaba sin mirar la cantidad.
        int unidades = unidadesPorProducto.getOrDefault(p.getId(), Math.max(1, it.quantity()));
        PricedAmount priced = pricingService.priceFor(p, v, unidades, escaleras.getOrDefault(p.getId(), List.of()));
        BigDecimal unit = priced.displayAmount();
        if (unit == null) {
            return null;
        }
        // El importe de la línea lo decide OrderAmounts: multiplica en DÓLARES sobre el precio canónico y
        // convierte al final, un solo redondeo. Multiplicar el unitario ya redondeado cobraba de más
        // —0,14 € × 100 = 14,00 € donde 100 unidades de 0,15 $ valen 13,80 €— y esa cifra es la que
        // acababa en la pasarela. El unitario se sigue enseñando redondeado porque es el precio que el
        // cliente eligió, pero ya no es la base de la cuenta.
        BigDecimal lineTotal = orderAmounts.lineSubtotal(priced.retailUsd(), it.quantity(), displayCode);
        return new CartQuoteLineOut(p.getId(), v != null ? v.getId() : null, unit, lineTotal,
                currencyService.formatDisplay(unit, displayCode), currencyService.formatDisplay(lineTotal, displayCode),
                pesoNetoDe(p, v));
    }

    /* =========================== HOME SECTIONS (DROP-20) =========================== */

    /*
     * La portada entera se cachea, no solo sus partes.
     *
     * Medido en pre el 5-sep-2026: 4,9 s por visita. El método hace SEIS operaciones caras en serie
     * —bestsellers, novedades, ventas, vídeo, el árbol de categorías aplanado y un recuento sobre toda la
     * tabla de productos— y solo dos de ellas estaban cacheadas. Las otras cuatro se rehacían en cada
     * carga de la portada, que es la página que más gente ve y la primera que ve.
     *
     * Se usa el MISMO almacén que el listado (`product-list`) a propósito: así los `@CacheEvict` que ya
     * existen sobre él —al tocar precios, promociones o el catálogo— refrescan también la portada. Con un
     * almacén propio habría que acordarse de invalidarlo en 152 sitios, y alguien se olvidaría.
     *
     * El generador de clave incluye la MONEDA, que es imprescindible: la respuesta lleva precios ya
     * calculados, y servir a un comprador en euros la copia cacheada en dólares sería un error de cobro.
     */
    @Override
    @Cacheable(value = CACHE_PRODUCT_LIST, keyGenerator = "currencyAwareKeyGenerator")
    @Transactional(readOnly = true)
    public HomeSectionsResponse homeSections(String lang, int perSection) {
        Pageable p = PageRequest.of(0, Math.min(perSection, 24));
        List<ProductSummaryView> trending = catalogUseCase.listBestsellers(null, p, lang).getContent();
        List<ProductSummaryView> newest = storefrontRead
                .productList(0, perSection, lang, null, null, null, null, null, NEWEST).items();
        List<ProductSummaryView> topSales = storefrontRead
                .productList(0, perSection, lang, null, null, null, null, null, "sales").items();
        // Filtrado en BD por hasVideo=true (antes traía 500 y filtraba en memoria: con el catálogo repoblado
        // los productos con vídeo caían fuera del lote y la sección salía vacía). Reutiliza el pageable ya
        // acotado (perSection topado a 24) para no dejar el tamaño de página a merced del cliente.
        List<ProductSummaryView> video = productRepository.findVisibleWithVideo(ProductStatus.ACTIVE, p).getContent()
                .stream().map(x -> productMapper.toSummary(x, lang)).toList();

        List<HomeSection> sections = new ArrayList<>();
        sections.add(new HomeSection("trending", "Trending Now", trending));
        sections.add(new HomeSection(NEWEST, "New Arrivals", newest));
        sections.add(new HomeSection("video", "Video Products", video));
        sections.add(new HomeSection("top_selling", "Top Selling", topSales));

        // Hot Categories — las categorías (de CUALQUIER nivel) con más productos directos.
        // Antes solo miraba raíces, pero los productos suelen estar en subcategorías (Calzado, Ropa de
        // mujer…), así que la home mostraba una sola. Ahora aplanamos el árbol y destacamos las que más
        // productos tienen. DROP-269: nunca una categoría con 0 productos.
        List<CategoryView> allCats = new ArrayList<>();
        flattenCategories(storefrontRead.categoriesTree(lang), allCats);
        List<CategoryView> hot = allCats.stream().filter(v -> v.directProductCount() > 0)
                .sorted((a, b) -> Integer.compare(b.directProductCount(), a.directProductCount())).limit(8).toList();
        // Los VISIBLES, no todos los activos: el catálogo solo lista los que tienen imagen espejada, y
        // anunciar en la portada un número mayor del que luego se puede recorrer es prometer de más.
        long totalProducts = productRepository.countVisibleByStatus(ProductStatus.ACTIVE);
        return new HomeSectionsResponse(sections, hot, totalProducts);
    }

    /**
     * Productos reales con los que la guía de bienvenida enseña las dos reglas que ahorran dinero en la
     * Unión Europea: el arancel se paga por partida declarada y el envío por bulto.
     *
     * <p>Se sirven con el precio ya calculado —el navegador no hace cuentas de dinero— y con el peso y la
     * clave de partida, que es lo que el simulador combina al sumar y restar unidades.
     *
     * <p>Cuando el país que mira no tiene derecho por artículo, {@code perArticleDutyFormatted} viene
     * vacío y la guía se salta ese paso: contarle el arancel de 3 EUR a quien compra desde fuera de la
     * Unión sería explicarle una regla que no le aplica.
     *
     * <p>Transaccional porque el mapeo lee las imágenes del producto, que son perezosas: sin una
     * transacción viva aquí, la colección revienta con LazyInitializationException al salir del servicio
     * que las cargó.
     */
    @Override
    @Transactional(readOnly = true)
    public StorefrontViews.WelcomeExamplesResponse welcomeExamples(String lang) {
        String pais = PricingCountryHolder.get();
        String divisa = CurrencyHolder.get();
        List<StorefrontViews.WelcomeExample> ejemplos = new ArrayList<>();
        for (ProductEntity p : welcomeExamples.examples()) {
            PricedAmount priced = pricingService.priceFor(p, null);
            if (priced.displayAmount() == null) {
                continue;
            }
            ProductSummaryView resumen = productMapper.toSummary(p, lang);
            ejemplos.add(new StorefrontViews.WelcomeExample(p.getId(), p.getSlug(), resumen.title(),
                    resumen.mainImage(), priced.displayFormatted(), priced.displayAmount(),
                    p.getWeightGrams() == null ? 0 : p.getWeightGrams(), welcomeExamples.dutyGroupOf(p),
                    priced.supplierShippingUsd() == null
                            ? BigDecimal.ZERO
                            : currencyService.usdToDisplay(priced.supplierShippingUsd())));
        }
        int derechoUsdCents = customsValuation.perArticleFeeUsdCents(pais);
        String derecho = derechoUsdCents <= 0
                ? ""
                : currencyService.formatDisplay(
                        currencyService.usdToDisplay(BigDecimal.valueOf(derechoUsdCents).movePointLeft(2)), divisa);
        int topeUsdCents = customsValuation.deMinimisUsdCentsFor(pais);
        return new StorefrontViews.WelcomeExamplesResponse(ejemplos, derecho, countryTaxService.rateBpsFor(pais),
                customsValuation.valuate(pais, 0, 0, List.of()).deMinimisLabel(),
                topeUsdCents <= 0
                        ? BigDecimal.ZERO
                        : currencyService.usdToDisplay(BigDecimal.valueOf(topeUsdCents).movePointLeft(2)));
    }

    /**
     * El desglose del simulador de la guía, con la aritmética REAL del checkout.
     *
     * <p>No replica el cálculo: llama al mismo {@code CheckoutPreviewService} que la vista previa del
     * pago. Así la guía no puede prometer una cosa y el checkout cobrar otra, y entran las dos fuentes de
     * la subvención —el porte que no se repite y la ganancia del pedido sobre el suelo—, la segunda de
     * las cuales depende del margen y por eso nunca podría calcularse en el navegador.
     *
     * <p>Solo acepta los productos que la propia guía propone y como mucho seis unidades de cada uno: es
     * un ejemplo con tres artículos, no una calculadora de precios abierta a cualquier catálogo.
     */
    @Override
    @Transactional(readOnly = true)
    public StorefrontViews.WelcomeSimulationResponse welcomeSimulate(
            List<StorefrontViews.WelcomeSimulationLine> lines) {
        String pais = PricingCountryHolder.get();
        String divisa = CurrencyHolder.get();
        Set<UUID> permitidos = welcomeExamples.examples().stream().map(ProductEntity::getId)
                .collect(Collectors.toSet());
        List<CheckoutPreviewService.Line> items = (lines == null
                ? List.<StorefrontViews.WelcomeSimulationLine>of()
                : lines).stream().filter(l -> l != null && l.productId() != null && permitidos.contains(l.productId()))
                .filter(l -> l.quantity() > 0)
                .map(l -> new CheckoutPreviewService.Line(l.productId(), null, Math.min(6, l.quantity()))).toList();
        if (items.isEmpty()) {
            String cero = currencyService.formatDisplay(BigDecimal.ZERO, divisa);
            return new StorefrontViews.WelcomeSimulationResponse(cero, cero, 0, cero, "", cero, "", cero, cero, 0, cero,
                    0, false, "");
        }
        CheckoutPreviewService.Preview p = checkoutPreview.compute(pais, null, items, null);
        CheckoutTotalsService.CheckoutTotals t = p.totals();
        int pesoGramos = items.stream().mapToInt(i -> productRepository.findById(i.productId())
                .map(pr -> ParcelAggregator.unitWeightGrams(pr, null) * i.quantity()).orElse(0)).sum();
        return new StorefrontViews.WelcomeSimulationResponse(currencyService.formatDisplay(p.subtotalDisplay(), divisa),
                enDivisa(t.customsHandlingCents(), divisa), partidasDe(t, pais),
                enDivisa(t.shippingBaseCents(), divisa),
                t.shippingSubsidyCents() > 0 ? enDivisa(t.shippingSubsidyCents(), divisa) : "",
                enDivisa(t.shippingNetCents(), divisa),
                t.customsSubsidyCents() > 0 ? enDivisa(t.customsSubsidyCents(), divisa) : "",
                enDivisa(t.customsNetCents(), divisa), currencyService.formatDisplay(p.taxDisplay(), divisa),
                p.taxRateBps(), currencyService.formatDisplay(p.totalDisplay(), divisa), pesoGramos,
                t.customs().blocked() || t.customs().deMinimisExceeded(),
                customsValuation.valuate(pais, 0, 0, List.of()).deMinimisLabel());
    }

    /**
     * Cuántas líneas de declaración lleva el pedido, deducidas del derecho ya calculado.
     *
     * <p>{@code CustomsValuation} no guarda el número —lo consume al multiplicar— así que se divide el
     * derecho total entre el de una línea. En la Unión el resto de recargos están a cero, de modo que la
     * división es exacta; donde no lo fueran, se devuelve 0 antes que un número inventado.
     */
    private int partidasDe(CheckoutTotalsService.CheckoutTotals t, String pais) {
        int porLinea = customsValuation.perArticleFeeUsdCents(pais);
        if (porLinea <= 0 || t.customsHandlingCents() <= 0) {
            return 0;
        }
        return t.customsHandlingCents() / porLinea;
    }

    /** Un importe en céntimos de dólar, ya convertido y formateado en la divisa del visitante. */
    private String enDivisa(int usdCents, String divisa) {
        return currencyService.formatDisplay(
                currencyService.usdToDisplay(BigDecimal.valueOf(Math.max(0, usdCents)).movePointLeft(2)), divisa);
    }

    /** Aplana el árbol de categorías (raíces + todas sus descendientes) en una lista plana. */
    private static void flattenCategories(List<CategoryView> nodes, List<CategoryView> out) {
        if (nodes == null) {
            return;
        }
        for (CategoryView n : nodes) {
            out.add(n);
            flattenCategories(n.children(), out);
        }
    }

    /* =========================== IMPORT BY URL (DROP-15) =========================== */

    @Override
    @Transactional(readOnly = true)
    public ImportUrlResponse importByUrl(ImportUrlRequest req, String lang) {
        if (req == null || req.url() == null)
            throw new BusinessException("url is required");
        String[] parsed = parseExternalUrl(req.url());
        String source = parsed[0];
        String externalId = parsed[1];
        if (source == null || externalId == null) {
            return new ImportUrlResponse(false, null, null, null,
                    "Pegar una URL de 1688, taobao, aliexpress o ebay (ej. https://detail.1688.com/offer/<id>.html)");
        }
        return productRepository.findBySourceAndExternalId(source, externalId)
                .map(p -> new ImportUrlResponse(true, source, externalId, productMapper.toSummary(p, lang), null))
                .orElse(new ImportUrlResponse(false, source, externalId, null, "Detectada URL de " + source
                        + " — esa oferta aún no está en el catálogo (puedes solicitar el sourcing en /sourcing)."));
    }

    /** Returns {source, externalId} or {null, null} when nothing matches. */
    private static String[] parseExternalUrl(String url) {
        if (url == null)
            return new String[]{null, null};
        String low = url.toLowerCase();
        Matcher m;
        if (low.contains("1688.com") || low.contains("1688.cn")) {
            m = Pattern.compile("offer[/_-](\\d+)\\.html").matcher(low);
            if (m.find())
                return new String[]{"1688", "OFFER-" + m.group(1)};
        }
        if (low.contains("taobao.com")) {
            m = Pattern.compile("id=(\\d+)").matcher(low);
            if (m.find())
                return new String[]{"taobao", m.group(1)};
        }
        if (low.contains("aliexpress.com")) {
            m = Pattern.compile("item/(\\d+)\\.html").matcher(low);
            if (m.find())
                return new String[]{"aliexpress", m.group(1)};
        }
        if (low.contains("ebay.com")) {
            m = Pattern.compile("/itm/(?:[^/]+/)?(\\d+)").matcher(low);
            if (m.find())
                return new String[]{"ebay", m.group(1)};
        }
        return new String[]{null, null};
    }

    /* =========================== IMAGE SEARCH MOCK (DROP-16) =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<ImageSearchResult> searchByImage(ImageSearchRequest req, String lang) {
        // MVP: no real embedding model deployed yet — deterministic score from image hash.
        Random r = new Random(imageSeed(req) | 1);
        int limit = req.limit() != null ? Math.min(req.limit(), 24) : 12;

        List<ProductEntity> pool = new ArrayList<>(
                productRepository.findVisibleByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 200)).getContent());
        Collections.shuffle(pool, r);
        return pool.stream().limit(limit)
                .map(p -> new ImageSearchResult(productMapper.toSummary(p, lang), 0.7 + r.nextDouble() * 0.29))
                .sorted((a, b) -> Double.compare(b.score(), a.score())).toList();
    }

    /**
     * Semilla determinista de la búsqueda por imagen. La imagen subida (base64) manda sobre la URL: si
     * llegan las dos, la subida es la que el usuario acaba de elegir. Sin ninguna de las dos, semilla 0 —
     * la misma petición devuelve siempre los mismos resultados.
     */
    private static int imageSeed(ImageSearchRequest req) {
        if (req.imageBase64() != null) {
            return req.imageBase64().hashCode();
        }
        return req.imageUrl() != null ? req.imageUrl().hashCode() : 0;
    }

    /* =========================== PRICE/STOCK HISTORY (DROP-25) =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<HistoryPoint> priceHistory(UUID id, int days) {
        // La ventana se calcula en UTC, igual que se fechan las instantáneas de product_history: dejarla
        // en la zona de la máquina haría que el mismo "últimos N días" devolviera un día más o un día
        // menos según dónde corra el servidor.
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(Math.min(days, 365));
        return historyRepository.findByProduct_IdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(id, from)
                .stream().map(h -> new HistoryPoint(h.getSnapshotDate(),
                        BigDecimal.valueOf(h.getPriceUsdCents()).movePointLeft(2), h.getStock()))
                .toList();
    }

    /* =========================== MARGIN ESTIMATE (DROP-24) =========================== */

    @Override
    @Transactional(readOnly = true)
    public MarginEstimate marginEstimate(UUID id, String country, int quantity, UUID variantId) {
        ProductEntity p = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product"));
        int qty = Math.max(1, quantity);
        RoundingMode halfUp = RoundingMode.HALF_UP;
        // DROP-675: si se indica una variante, el envío usa su peso/dimensiones reales (no el del producto).
        ProductVariantEntity variant = variantId == null
                ? null
                : p.getVariants().stream().filter(v -> variantId.equals(v.getId())).findFirst().orElse(null);
        CostBasis basis = costBasis(p, qty);
        BigDecimal costUsd = basis.unitSource() != null
                ? currencyService.toUsd(basis.unitSource(), basis.sourceCurrency())
                : BigDecimal.ZERO;
        // DROP-678: el retail sugerido aplica la REGLA DE MARGEN configurada (MarginService), no un x2.5.
        MarginService.PriceWithMargin withMargin = marginService.apply(costUsd, p, null);
        BigDecimal retailUsd = withMargin.retailUsd() != null ? withMargin.retailUsd() : costUsd;
        BigDecimal shippingUsd = cheapestShippingUsd(p, variant, country);
        // Comisión de plataforma configurable (DROP-680: por defecto 0; no se inventa).
        BigDecimal commPct = platformCommissionPct != null ? platformCommissionPct : BigDecimal.ZERO;
        BigDecimal commissionUsd = retailUsd.multiply(commPct).movePointLeft(2).setScale(2, halfUp);
        BigDecimal netUsd = retailUsd.subtract(costUsd).subtract(shippingUsd).subtract(commissionUsd)
                .multiply(BigDecimal.valueOf(qty)).setScale(2, halfUp);
        BigDecimal marginPct = retailUsd.signum() == 0
                ? BigDecimal.ZERO
                : netUsd.divide(retailUsd.multiply(BigDecimal.valueOf(qty)), 4, halfUp)
                        .multiply(BigDecimal.valueOf(100));
        // Presentar en la moneda activa (coherente con el resto de la tienda, vía X-Currency).
        String displayCode = pricingService.displayCurrencyCode();
        return new MarginEstimate(currencyService.usdToDisplay(costUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(retailUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(shippingUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(commissionUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(netUsd).setScale(2, halfUp), marginPct.setScale(1, halfUp), displayCode,
                withMargin.appliedPercentage(), basis.appliedTierMinQty());
    }

    /** Precio unitario de origen del que parte la estimación, su divisa y el tramo que lo justifica. */
    private record CostBasis(BigDecimal unitSource, String sourceCurrency, Integer appliedTierMinQty) {
    }

    /**
     * DROP-669: el coste parte del TRAMO de precio real aplicable a la cantidad (price break), no de un
     * precio plano. Si no hay tramos se usa el precio unitario base y {@code appliedTierMinQty} queda a
     * null, que es lo que el front pinta como "sin tramo aplicado".
     */
    private CostBasis costBasis(ProductEntity p, int qty) {
        ProductPriceTierEntity tier = applicableTier(p.getId(), qty);
        if (tier != null && tier.getUnitPrice() != null) {
            // El tramo puede no traer divisa propia: entonces vale la del producto.
            String currency = tier.getCurrency() != null ? tier.getCurrency() : productCurrency(p);
            return new CostBasis(tier.getUnitPrice(), currency, tier.getMinQty());
        }
        return new CostBasis(p.getBasePrice(), productCurrency(p), null);
    }

    /** Divisa de origen del producto; CNY por defecto, que es la del catálogo importado de 1688. */
    private static String productCurrency(ProductEntity p) {
        return p.getCurrency() != null ? p.getCurrency() : "CNY";
    }

    /**
     * Envío real por destino (base_cents/per_kg_cents en USD) con el peso real del paquete: se toma la
     * tarifa MÁS BARATA de las activas, que es la que el usuario esperaría ver en una estimación.
     */
    private BigDecimal cheapestShippingUsd(ProductEntity p, ProductVariantEntity variant, String country) {
        if (p.getSupplier() == null) {
            return BigDecimal.ZERO;
        }
        List<ShippingRateEntity> rates = rateRepository
                .findBySupplier_IdAndCountryCodeAndActiveTrue(p.getSupplier().getId(), country.toUpperCase());
        // DROP-675: peso de envío real, priorizando el de la variante seleccionada.
        int grams = shippingGrams(p, variant);
        return rates.stream().map(r -> {
            double kg = grams / 1000.0;
            long cents = r.getBaseCents() + Math.round(r.getPerKgCents() * kg);
            return BigDecimal.valueOf(cents).movePointLeft(2);
        }).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    /**
     * DROP-675: gramos para el cálculo de envío. Prioriza el peso del paquete/unidad de la variante
     * seleccionada y, si falta, el del producto; 500 g como último recurso cuando no hay dato real.
     */
    private int shippingGrams(ProductEntity p, ProductVariantEntity variant) {
        if (variant != null) {
            if (variant.getPackageWeightGrams() != null) {
                return variant.getPackageWeightGrams();
            }
            if (variant.getWeightGrams() != null) {
                return variant.getWeightGrams();
            }
        }
        if (p.getPackageWeightGrams() != null) {
            return p.getPackageWeightGrams();
        }
        return p.getWeightGrams() != null ? p.getWeightGrams() : 500;
    }

    /** DROP-669: tramo de precio real cuyo rango [minQty,maxQty] contiene la cantidad (o {@code null}). */
    private ProductPriceTierEntity applicableTier(UUID productId, int qty) {
        List<ProductPriceTierEntity> tiers = priceTierRepository.findByProductIdOrderByMinQtyAsc(productId);
        ProductPriceTierEntity best = null;
        for (ProductPriceTierEntity t : tiers) {
            Integer max = t.getMaxQty();
            if (qty >= t.getMinQty() && (max == null || qty <= max)) {
                best = t;
            }
        }
        return best;
    }

    /* =========================== INTERNAL (storefront-only) =========================== */

    private boolean matchesNeedle(ProductEntity p, String needle) {
        if (p.getTitleZh() != null && p.getTitleZh().toLowerCase().contains(needle))
            return true;
        if (p.getExternalId() != null && p.getExternalId().toLowerCase().contains(needle))
            return true;
        if (p.getSlug() != null && p.getSlug().toLowerCase().contains(needle))
            return true;
        return p.getTranslations() != null && p.getTranslations().stream()
                .anyMatch(t -> t.getTitle() != null && t.getTitle().toLowerCase().contains(needle));
    }

    private String translatedTitle(ProductEntity p, String lang) {
        // El idioma se comprueba aquí y no se confía en el defaultValue del @RequestParam: quien llame a
        // este método desde otro sitio no tiene por qué saber que un nulo lo hacía reventar.
        if (p.getTranslations() == null || lang == null)
            return null;
        return p.getTranslations().stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage()))
                .map(ProductTranslationEntity::getTitle).findFirst().orElse(null);
    }

    private int effectiveWeight(ProductEntity p) {
        if (p.getPackageWeightGrams() != null)
            return p.getPackageWeightGrams();
        if (p.getWeightGrams() != null)
            return p.getWeightGrams();
        return 500; // sane default 500g
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    @Override
    public List<LivePromotionView> livePromotions(String lang) {
        return promotionShowcase.live(lang);
    }
}
