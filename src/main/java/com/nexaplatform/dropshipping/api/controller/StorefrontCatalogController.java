package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.LivePromotionView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SupplierView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.VariantView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SpecificationView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.AttributeView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.TagView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingZoneView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingRateView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingQuoteItem;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ShippingQuoteRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.AttributeKeyView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SuggestionView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteItemIn;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteLineOut;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteOut;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HomeSection;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HomeSectionsResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImportUrlRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImportUrlResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImageSearchRequest;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.ImageSearchResult;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.HistoryPoint;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.MarginEstimate;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingRateEntity;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos;
import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.ProductDetailQueryService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionShowcaseService;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    public PageResponse<ProductSummaryView> list(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String shipFrom, Boolean freeShipping,
            Boolean selfPickup, Boolean hasVideo, Integer minRating, Integer inventoryMin, String certification,
            String sort, Boolean verified) {
        // El filtro de verificación es SOLO para admin: si el que consulta no es admin, se ignora.
        Boolean verifiedFilter = SecurityUtils.isAdmin() ? verified : null;
        return storefrontRead.productListFull(page, size, lang,
                new ProductListFilters(q, categoryId, supplierId, minPrice, maxPrice, shipFrom, freeShipping,
                        selfPickup, hasVideo, minRating, inventoryMin, certification, verifiedFilter),
                sort);
    }

    @Override
    public ProductDetailView detailBySlug(String slug, String lang) {
        return catalogUseCase.getProductBySlug(slug, lang);
    }

    @Override
    public ProductDetailView detailById(UUID id, String lang) {
        return catalogUseCase.getProductById(id, lang);
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
        return productDetailQuery.relatedProducts(id, limit).stream()
                .map(x -> productMapper.toSummary(x, lang)).toList();
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

        List<ShippingRateEntity> rates = rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), req.country().toUpperCase());
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
     * Cotiza el carrito con el precio ACTUAL de cada producto (margen + tasa del día, 2 decimales hacia
     * arriba por línea) en la moneda activa — EXACTAMENTE lo que se factura y se cobra. El carrito del
     * cliente "congela" el precio al añadir, así que el checkout debe re-cotizar aquí para que lo mostrado
     * coincida con lo cobrado (evita "veo X y me cobran Y" cuando el precio cambió tras añadir al carrito).
     */
    @PostMapping("/cart-quote")
    @Transactional(readOnly = true)
    public CartQuoteOut cartQuote(@RequestBody List<CartQuoteItemIn> items) {
        String displayCode = pricingService.displayCurrencyCode();
        List<CartQuoteLineOut> lines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartQuoteItemIn it : items == null ? List.<CartQuoteItemIn>of() : items) {
            CartQuoteLineOut line = quoteLine(it, displayCode);
            if (line != null) {
                lines.add(line);
                subtotal = subtotal.add(line.lineTotal());
            }
        }
        return new CartQuoteOut(displayCode, pricingService.displayCurrencySymbol(), lines, subtotal,
                currencyService.formatDisplay(subtotal, displayCode));
    }

    /**
     * Línea cotizada al precio actual, o {@code null} si no es cotizable (línea vacía, producto que ya no
     * existe o sin precio). Esas líneas se descartan en silencio a propósito: el carrito guardado en el
     * navegador puede arrastrar productos retirados del catálogo y no debe tumbar la cotización entera.
     */
    private CartQuoteLineOut quoteLine(CartQuoteItemIn it, String displayCode) {
        if (it == null || it.productId() == null) {
            return null;
        }
        ProductEntity p = productRepository.findById(it.productId()).orElse(null);
        if (p == null) {
            return null;
        }
        ProductVariantEntity v = it.variantId() == null ? null
                : p.getVariants().stream().filter(x -> it.variantId().equals(x.getId())).findFirst().orElse(null);
        BigDecimal unit = pricingService.priceFor(p, v).displayAmount();
        if (unit == null) {
            return null;
        }
        BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(Math.max(1, it.quantity())));
        return new CartQuoteLineOut(p.getId(), v != null ? v.getId() : null, unit, lineTotal,
                currencyService.formatDisplay(unit, displayCode),
                currencyService.formatDisplay(lineTotal, displayCode));
    }

    /* =========================== HOME SECTIONS (DROP-20) =========================== */



    @Override
    @Transactional(readOnly = true)
    public HomeSectionsResponse homeSections(String lang, int perSection) {
        Pageable p = PageRequest.of(0, Math.min(perSection, 24));
        List<ProductSummaryView> trending = catalogUseCase.listBestsellers(null, p, lang).getContent();
        List<ProductSummaryView> newest = storefrontRead.productList(0, perSection, lang, null, null, null, null, null, NEWEST).items();
        List<ProductSummaryView> topSales = storefrontRead.productList(0, perSection, lang, null, null, null, null, null, "sales").items();
        // Filtrado en BD por hasVideo=true (antes traía 500 y filtraba en memoria: con el catálogo repoblado
        // los productos con vídeo caían fuera del lote y la sección salía vacía). Reutiliza el pageable ya
        // acotado (perSection topado a 24) para no dejar el tamaño de página a merced del cliente.
        List<ProductSummaryView> video = productRepository.findVisibleWithVideo(ProductStatus.ACTIVE, p)
                .getContent().stream().map(x -> productMapper.toSummary(x, lang)).toList();

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
        long totalProducts = productRepository.countByStatus(ProductStatus.ACTIVE);
        return new HomeSectionsResponse(sections, hot, totalProducts);
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
        ProductEntity p = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product"));
        int qty = Math.max(1, quantity);
        RoundingMode halfUp = RoundingMode.HALF_UP;
        // DROP-675: si se indica una variante, el envío usa su peso/dimensiones reales (no el del producto).
        ProductVariantEntity variant = variantId == null ? null : p.getVariants().stream()
                .filter(v -> variantId.equals(v.getId())).findFirst().orElse(null);
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
        return new MarginEstimate(
                currencyService.usdToDisplay(costUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(retailUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(shippingUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(commissionUsd).setScale(2, halfUp),
                currencyService.usdToDisplay(netUsd).setScale(2, halfUp),
                marginPct.setScale(1, halfUp), displayCode, withMargin.appliedPercentage(),
                basis.appliedTierMinQty());
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
