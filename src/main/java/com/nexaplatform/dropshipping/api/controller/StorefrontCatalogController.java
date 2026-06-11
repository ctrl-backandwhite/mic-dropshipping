package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.CatalogService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Public catalog API. Mirrors what an integrator needs to list, search and inspect every
 * piece of a 1688-style product — categories, products, variants, specs, attributes, tags
 * and shipping coverage — without ever touching admin endpoints.
 */
@RestController
@RequestMapping("/api/storefront/catalog")
@RequiredArgsConstructor
public class StorefrontCatalogController implements StorefrontCatalogApi {

    private final CatalogService catalogService;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductSpecificationRepository specRepository;
    private final ProductAttributeRepository attributeRepository;
    private final ProductTagRepository tagRepository;
    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ProductHistoryRepository historyRepository;

    /* =========================== VIEW RECORDS =========================== */

    public record CategoryView(UUID id, String slug, String name, String nameZh,
                               UUID parentId, int position, String icon,
                               int directProductCount, List<CategoryView> children) {}

    public record CategoryBreadcrumb(UUID id, String slug, String name) {}

    public record SupplierView(UUID id, String slug, String name, String nameZh,
                               String country, String city, BigDecimal rating,
                               Integer yearsActive, boolean verified, boolean trustPass,
                               long productCount) {}

    public record VariantView(UUID id, String sku, String externalId, String title,
                              BigDecimal price, int stock, String imageUrl,
                              Map<String, String> options, boolean active) {}

    public record SpecificationView(String key, String value, int position) {}

    public record AttributeView(String key, String value) {}

    public record TagView(String tag) {}

    public record ShippingZoneView(UUID supplierId, String supplierName, String countryCode,
                                   String region, boolean active) {}

    public record ShippingRateView(UUID id, UUID supplierId, String countryCode,
                                   String method, String carrier,
                                   int transitDaysMin, int transitDaysMax,
                                   BigDecimal baseCost, BigDecimal perKgCost,
                                   Integer maxWeightGrams) {}

    public record ShippingQuoteItem(UUID supplierId, String method, String carrier,
                                    int transitDaysMin, int transitDaysMax,
                                    BigDecimal cost, String currency) {}

    public record ShippingQuoteRequest(UUID productId, UUID variantId, int quantity, String country) {}

    public record AttributeKeyView(String key, long usage) {}

    public record SuggestionView(String type, String text, String slug) {}

    /* =========================== CATEGORIES =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> categoriesFlat(String lang) {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition))
                .map(c -> categoryView(c, lang, false))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> categoriesTree(String lang) {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getParent() == null)
                .sorted(Comparator.comparingInt(CategoryEntity::getPosition))
                .map(c -> categoryView(c, lang, true))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryView categoryDetail(String idOrSlug,
                                       String lang) {
        return categoryView(resolveCategory(idOrSlug), lang, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> categoryChildren(String idOrSlug,
                                               String lang) {
        UUID parentId = resolveCategory(idOrSlug).getId();
        return categoryRepository.findByParent_IdOrderByPositionAsc(parentId).stream()
                .map(c -> categoryView(c, lang, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryBreadcrumb> categoryBreadcrumb(String idOrSlug,
                                                       String lang) {
        List<CategoryBreadcrumb> out = new ArrayList<>();
        CategoryEntity c = resolveCategory(idOrSlug);
        while (c != null) {
            out.add(0, new CategoryBreadcrumb(c.getId(), c.getSlug(), translatedName(c, lang)));
            c = c.getParent();
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsByCategory(
            String idOrSlug,
            int page,
            int size,
            String lang,
            String sort) {
        UUID categoryId = resolveCategory(idOrSlug).getId();
        return productList(page, size, lang, null, categoryId, null, null, null, sort);
    }

    /* =========================== SUPPLIERS =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<SupplierView> suppliers() {
        return supplierRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(this::supplierView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierView supplierDetail(UUID id) {
        return supplierView(supplierRepository.findById(id).orElseThrow(() -> new NotFoundException("Supplier")));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> productsBySupplier(
            UUID id,
            int page,
            int size,
            String lang,
            String sort) {
        return productList(page, size, lang, null, null, id, null, null, sort);
    }

    /* =========================== PRODUCTS =========================== */

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryView> list(
            int page,
            int size,
            String lang,
            String q,
            UUID categoryId,
            UUID supplierId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String shipFrom,
            Boolean freeShipping,
            Boolean selfPickup,
            Boolean hasVideo,
            Integer minRating,
            Integer inventoryMin,
            String certification,
            String sort) {
        return productListFull(page, size, lang, q, categoryId, supplierId, minPrice, maxPrice,
                shipFrom, freeShipping, selfPickup, hasVideo, minRating, inventoryMin, certification, sort);
    }

    @Override
    public ProductDetailView detailBySlug(String slug,
                                          String lang) {
        return catalogService.getProductBySlug(slug, lang);
    }

    @Override
    public ProductDetailView detailById(UUID id,
                                        String lang) {
        return catalogService.getProductById(id, lang);
    }

    @Override
    public ProductDetailView detailByExternal(String source,
                                              String externalId,
                                              String lang) {
        ProductEntity p = productRepository.findBySourceAndExternalId(source, externalId)
                .orElseThrow(() -> new NotFoundException("Product"));
        return catalogService.getProductById(p.getId(), lang);
    }

    @Override
    public PageResponse<ProductSummaryView> bestsellers(
            UUID categoryId,
            int page,
            int size,
            String lang) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.from(catalogService.listBestsellers(categoryId, pageable, lang));
    }

    @Override
    public PageResponse<ProductSummaryView> trending(
            UUID categoryId,
            int page,
            int size,
            String lang) {
        return bestsellers(categoryId, page, size, lang);
    }

    @Override
    public PageResponse<ProductSummaryView> newest(
            int page,
            int size,
            String lang) {
        return productList(page, size, lang, null, null, null, null, null, "newest");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductSummaryView> relatedProducts(UUID id,
                                                    String lang,
                                                    int limit) {
        ProductEntity p = productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product"));
        UUID catId = p.getCategory() != null ? p.getCategory().getId() : null;
        return productRepository.findAll().stream()
                .filter(x -> x.getStatus() == ProductStatus.ACTIVE)
                .filter(x -> !x.getId().equals(id))
                .filter(x -> catId == null || (x.getCategory() != null && catId.equals(x.getCategory().getId())))
                .sorted((a, b) -> {
                    BigDecimal ta = a.getTrendScore() == null ? BigDecimal.ZERO : a.getTrendScore();
                    BigDecimal tb = b.getTrendScore() == null ? BigDecimal.ZERO : b.getTrendScore();
                    return tb.compareTo(ta);
                })
                .limit(limit)
                .map(x -> catalogService.toSummaryView(x, lang))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpecificationView> specifications(UUID id,
                                                  String lang) {
        var specs = specRepository.findByProduct_IdAndLocaleOrderByPositionAsc(id, lang);
        if (specs.isEmpty()) specs = specRepository.findByProduct_IdAndLocaleOrderByPositionAsc(id, "en");
        if (specs.isEmpty()) specs = specRepository.findByProduct_IdOrderByPositionAsc(id);
        return specs.stream()
                .map(s -> new SpecificationView(s.getSpecKey(), s.getSpecValue(), s.getPosition()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttributeView> attributes(UUID id) {
        return attributeRepository.findByProduct_Id(id).stream()
                .map(a -> new AttributeView(a.getAttrKey(), a.getAttrValue()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> tags(UUID id) {
        return tagRepository.findByProduct_Id(id).stream()
                .map(ProductTagEntity::getTag).toList();
    }

    @Override
    public List<CatalogImageDtoOut> images(UUID id) {
        return catalogService.listProductImages(id);
    }

    @Override
    public List<CatalogPriceTierDtoOut> priceTiers(UUID id) {
        return catalogService.listProductPriceTiers(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SuggestionView> suggest(String q,
                                        String lang,
                                        int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        if (needle.isEmpty()) return List.of();
        return productRepository.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 200))
                .getContent().stream()
                .filter(p -> matchesNeedle(p, needle))
                .limit(limit)
                .map(p -> new SuggestionView("product",
                        firstNonNull(translatedTitle(p, lang), p.getTitleZh()),
                        p.getSlug()))
                .toList();
    }

    /* =========================== VARIANTS =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<VariantView> variantsForProduct(UUID id) {
        return variantRepository.findByProductId(id).stream()
                .filter(ProductVariantEntity::isActive)
                .map(this::variantView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VariantView variantById(UUID id) {
        return variantView(variantRepository.findById(id).orElseThrow(() -> new NotFoundException("Variant")));
    }

    @Override
    @Transactional(readOnly = true)
    public VariantView variantBySku(UUID productId, String sku) {
        return variantRepository.findByProductId(productId).stream()
                .filter(v -> sku.equalsIgnoreCase(v.getSku()) || sku.equalsIgnoreCase(v.getExternalId()))
                .findFirst()
                .map(this::variantView)
                .orElseThrow(() -> new NotFoundException("Variant"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantView> variantMatch(UUID productId,
                                          Map<String, String> options) {
        return variantRepository.findByProductId(productId).stream()
                .filter(v -> options.entrySet().stream().allMatch(e ->
                        e.getValue().equalsIgnoreCase(v.getOptions().get(e.getKey()))))
                .map(this::variantView).toList();
    }

    /* =========================== ATTRIBUTES (taxonomy) =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<AttributeKeyView> attributeKeys() {
        Map<String, Long> agg = attributeRepository.findAll().stream()
                .collect(Collectors.groupingBy(ProductAttributeEntity::getAttrKey, Collectors.counting()));
        return agg.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new AttributeKeyView(e.getKey(), e.getValue()))
                .toList();
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
    public List<ProductSummaryView> productsByTag(String tag,
                                                  String lang,
                                                  int limit) {
        List<UUID> ids = tagRepository.findProductIdsByTag(tag);
        return productRepository.findAllById(ids).stream()
                .filter(p -> p.getStatus() == ProductStatus.ACTIVE)
                .limit(limit)
                .map(p -> catalogService.toSummaryView(p, lang)).toList();
    }

    /* =========================== SHIPPING =========================== */

    @Override
    @Transactional(readOnly = true)
    public List<ShippingZoneView> shippingZones(UUID supplierId) {
        return zoneRepository.findBySupplier_IdAndActiveTrueOrderByCountryCodeAsc(supplierId).stream()
                .map(z -> new ShippingZoneView(
                        z.getSupplier().getId(), z.getSupplier().getName(),
                        z.getCountryCode(), z.getRegion(), z.isActive()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingRateView> shippingRates(UUID supplierId,
                                                String country) {
        return rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(supplierId, country.toUpperCase()).stream()
                .map(r -> new ShippingRateView(
                        r.getId(), r.getSupplier().getId(), r.getCountryCode(),
                        r.getMethod(), r.getCarrier(),
                        r.getTransitDaysMin(), r.getTransitDaysMax(),
                        BigDecimal.valueOf(r.getBaseCents()).movePointLeft(2),
                        BigDecimal.valueOf(r.getPerKgCents()).movePointLeft(2),
                        r.getMaxWeightGrams()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingQuoteItem> shippingQuote(ShippingQuoteRequest req) {
        ProductEntity p = productRepository.findById(req.productId())
                .orElseThrow(() -> new NotFoundException("Product"));
        SupplierEntity s = p.getSupplier();
        if (s == null) throw new NotFoundException("Supplier");
        int unitGrams = effectiveWeight(p);
        int totalGrams = unitGrams * Math.max(1, req.quantity());

        var rates = rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(s.getId(), req.country().toUpperCase());
        return rates.stream()
                .filter(r -> r.getMaxWeightGrams() == null || totalGrams <= r.getMaxWeightGrams())
                .filter(r -> r.getMinWeightGrams() == null || totalGrams >= r.getMinWeightGrams())
                .map(r -> {
                    double kg = totalGrams / 1000.0;
                    long costCents = r.getBaseCents() + Math.round(r.getPerKgCents() * kg);
                    BigDecimal cost = BigDecimal.valueOf(costCents).movePointLeft(2);
                    return new ShippingQuoteItem(s.getId(), r.getMethod(), r.getCarrier(),
                            r.getTransitDaysMin(), r.getTransitDaysMax(), cost, "USD");
                })
                .sorted(Comparator.comparing(ShippingQuoteItem::cost))
                .toList();
    }

    /* =========================== HOME SECTIONS (DROP-20) =========================== */

    public record HomeSection(String code, String title, List<ProductSummaryView> items) {}
    public record HomeSectionsResponse(List<HomeSection> sections, List<CategoryView> hotCategories) {}

    @Override
    @Transactional(readOnly = true)
    public HomeSectionsResponse homeSections(String lang,
                                             int perSection) {
        Pageable p = PageRequest.of(0, Math.min(perSection, 24));
        var trending  = catalogService.listBestsellers(null, p, lang).getContent();
        var newest    = productList(0, perSection, lang, null, null, null, null, null, "newest").items();
        var topSales  = productList(0, perSection, lang, null, null, null, null, null, "sales").items();
        var video     = productRepository.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 500))
                .getContent().stream()
                .filter(x -> Boolean.TRUE.equals(x.getHasVideo()))
                .limit(perSection)
                .map(x -> catalogService.toSummaryView(x, lang)).toList();

        List<HomeSection> sections = new ArrayList<>();
        sections.add(new HomeSection("trending",     "Trending Now",         trending));
        sections.add(new HomeSection("newest",       "New Arrivals",         newest));
        sections.add(new HomeSection("video",        "Video Products",       video));
        sections.add(new HomeSection("top_selling",  "Top Selling",          topSales));

        // Hot Categories — root cats with the highest direct product count.
        // DROP-269: never surface a category with zero products on the homepage.
        var hot = categoryRepository.findAll().stream()
                .filter(c -> c.getParent() == null)
                .map(c -> categoryView(c, lang, false))
                .filter(v -> v.directProductCount() > 0)
                .sorted((a, b) -> Integer.compare(b.directProductCount(), a.directProductCount()))
                .limit(8).toList();
        return new HomeSectionsResponse(sections, hot);
    }

    /* =========================== IMPORT BY URL (DROP-15) =========================== */

    public record ImportUrlRequest(@jakarta.validation.constraints.NotBlank String url) {}
    public record ImportUrlResponse(boolean matched, String source, String externalId,
                                    ProductSummaryView product, String resolveHint) {}

    @Override
    @Transactional(readOnly = true)
    public ImportUrlResponse importByUrl(ImportUrlRequest req,
                                         String lang) {
        if (req == null || req.url() == null) throw new com.nexaplatform.dropshipping.api.exception.BusinessException("url is required");
        String[] parsed = parseExternalUrl(req.url());
        String source = parsed[0], externalId = parsed[1];
        if (source == null || externalId == null) {
            return new ImportUrlResponse(false, null, null, null,
                    "Pegar una URL de 1688, taobao, aliexpress o ebay (ej. https://detail.1688.com/offer/<id>.html)");
        }
        return productRepository.findBySourceAndExternalId(source, externalId)
                .map(p -> new ImportUrlResponse(true, source, externalId,
                        catalogService.toSummaryView(p, lang), null))
                .orElse(new ImportUrlResponse(false, source, externalId, null,
                        "Detectada URL de " + source + " — esa oferta aún no está en el catálogo (puedes solicitar el sourcing en /sourcing)."));
    }

    /** Returns {source, externalId} or {null, null} when nothing matches. */
    private static String[] parseExternalUrl(String url) {
        if (url == null) return new String[]{ null, null };
        String low = url.toLowerCase();
        java.util.regex.Matcher m;
        if (low.contains("1688.com") || low.contains("1688.cn")) {
            m = java.util.regex.Pattern.compile("offer[/_-](\\d+)\\.html").matcher(low);
            if (m.find()) return new String[]{ "1688", "OFFER-" + m.group(1) };
        }
        if (low.contains("taobao.com")) {
            m = java.util.regex.Pattern.compile("id=(\\d+)").matcher(low);
            if (m.find()) return new String[]{ "taobao", m.group(1) };
        }
        if (low.contains("aliexpress.com")) {
            m = java.util.regex.Pattern.compile("item/(\\d+)\\.html").matcher(low);
            if (m.find()) return new String[]{ "aliexpress", m.group(1) };
        }
        if (low.contains("ebay.com")) {
            m = java.util.regex.Pattern.compile("/itm/(?:[^/]+/)?(\\d+)").matcher(low);
            if (m.find()) return new String[]{ "ebay", m.group(1) };
        }
        return new String[]{ null, null };
    }

    /* =========================== IMAGE SEARCH MOCK (DROP-16) =========================== */

    public record ImageSearchRequest(String imageBase64, String imageUrl, Integer limit) {}
    public record ImageSearchResult(ProductSummaryView product, double score) {}

    @Override
    @Transactional(readOnly = true)
    public List<ImageSearchResult> searchByImage(ImageSearchRequest req,
                                                 String lang) {
        // MVP: no real embedding model deployed yet — fall back to trending products with a
        // deterministic score derived from the image hash so the UX flow is exercised end to end.
        // Replace with vector-search against an embedding column once the ML side is wired.
        int seed = (req.imageBase64() != null ? req.imageBase64().hashCode()
                  : req.imageUrl()    != null ? req.imageUrl().hashCode() : 0);
        java.util.Random r = new java.util.Random(seed | 1);
        int limit = req.limit() != null ? Math.min(req.limit(), 24) : 12;

        List<ProductEntity> pool = new ArrayList<>(productRepository.findByStatus(ProductStatus.ACTIVE,
                PageRequest.of(0, 200)).getContent());
        java.util.Collections.shuffle(pool, r);
        return pool.stream().limit(limit)
                .map(p -> new ImageSearchResult(catalogService.toSummaryView(p, lang),
                        0.7 + r.nextDouble() * 0.29))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    /* =========================== PRICE/STOCK HISTORY (DROP-25) =========================== */

    public record HistoryPoint(java.time.LocalDate date, java.math.BigDecimal price, int stock) {}

    @Override
    @Transactional(readOnly = true)
    public List<HistoryPoint> priceHistory(UUID id,
                                           int days) {
        java.time.LocalDate from = java.time.LocalDate.now().minusDays(Math.min(days, 365));
        return historyRepository
                .findByProduct_IdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(id, from)
                .stream()
                .map(h -> new HistoryPoint(h.getSnapshotDate(),
                        BigDecimal.valueOf(h.getPriceUsdCents()).movePointLeft(2),
                        h.getStock()))
                .toList();
    }

    /* =========================== MARGIN ESTIMATE (DROP-24) =========================== */

    public record MarginEstimate(BigDecimal cost, BigDecimal suggestedRetail,
                                 BigDecimal shipping, BigDecimal commission,
                                 BigDecimal netProfit, BigDecimal marginPct) {}

    @Override
    @Transactional(readOnly = true)
    public MarginEstimate marginEstimate(UUID id,
                                         String country,
                                         int quantity) {
        ProductEntity p = productRepository.findById(id)
                .orElseThrow(() -> new com.nexaplatform.dropshipping.api.exception.NotFoundException("Product"));
        // Cost = product base price (in USD after pricing engine — already converted here).
        BigDecimal cost = p.getBasePrice() == null ? BigDecimal.ZERO : p.getBasePrice();
        // Suggested retail = cost × 2.5 (typical dropshipping marker) capped at 4×.
        BigDecimal retail = cost.multiply(new BigDecimal("2.5")).setScale(2, java.math.RoundingMode.HALF_UP);
        // Shipping: cheapest STANDARD method to destination (if supplier covers it).
        BigDecimal shipping = BigDecimal.ZERO;
        if (p.getSupplier() != null) {
            var rates = rateRepository.findBySupplier_IdAndCountryCodeAndActiveTrue(
                    p.getSupplier().getId(), country.toUpperCase());
            shipping = rates.stream()
                    .map(r -> {
                        double kg = (p.getPackageWeightGrams() != null ? p.getPackageWeightGrams()
                                  : p.getWeightGrams() != null ? p.getWeightGrams() : 500) / 1000.0;
                        long cents = r.getBaseCents() + Math.round(r.getPerKgCents() * kg);
                        return BigDecimal.valueOf(cents).movePointLeft(2);
                    })
                    .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        }
        // Marketplace commission ~12% on the retail.
        BigDecimal commission = retail.multiply(new BigDecimal("0.12")).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal net = retail.subtract(cost).subtract(shipping).subtract(commission)
                .multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal marginPct = retail.signum() == 0
                ? BigDecimal.ZERO
                : net.divide(retail.multiply(BigDecimal.valueOf(quantity)), 4, java.math.RoundingMode.HALF_UP)
                     .multiply(BigDecimal.valueOf(100));
        return new MarginEstimate(cost, retail, shipping, commission, net, marginPct);
    }

    /* =========================== INTERNAL =========================== */

    private CategoryEntity resolveCategory(String idOrSlug) {
        try {
            UUID uuid = UUID.fromString(idOrSlug);
            return categoryRepository.findById(uuid).orElseThrow(() -> new NotFoundException("Category"));
        } catch (IllegalArgumentException notUuid) {
            return categoryRepository.findBySlug(idOrSlug).orElseThrow(() -> new NotFoundException("Category"));
        }
    }

    private CategoryView categoryView(CategoryEntity c, String lang, boolean withChildren) {
        List<CategoryView> children = withChildren
                ? categoryRepository.findByParent_IdOrderByPositionAsc(c.getId()).stream()
                        .map(child -> categoryView(child, lang, true)).toList()
                : List.of();
        long count = productRepository.findAll().stream()
                .filter(p -> p.getCategory() != null && c.getId().equals(p.getCategory().getId()))
                .count();
        return new CategoryView(c.getId(), c.getSlug(), translatedName(c, lang), c.getNameZh(),
                c.getParent() != null ? c.getParent().getId() : null,
                c.getPosition(), c.getIcon(), (int) count, children);
    }

    private SupplierView supplierView(SupplierEntity s) {
        long count = productRepository.findAll().stream()
                .filter(p -> p.getSupplier() != null && s.getId().equals(p.getSupplier().getId()))
                .count();
        return new SupplierView(s.getId(), s.getExternalId(), s.getName(), s.getNameZh(),
                s.getCountry(), s.getCity(), s.getRating(), s.getYearsActive(),
                s.isVerified(), s.isTrustPass(), count);
    }

    private VariantView variantView(ProductVariantEntity v) {
        String img = v.getImageCdnUrl() != null ? v.getImageCdnUrl() : v.getImageSourceUrl();
        return new VariantView(v.getId(), v.getSku(), v.getExternalId(), v.getTitle(),
                v.getPrice(), v.getStock(), img,
                v.getOptions() != null ? v.getOptions() : Map.of(), v.isActive());
    }

    /** Legacy 9-arg helper kept for category/supplier shortcut endpoints. */
    private PageResponse<ProductSummaryView> productList(int page, int size, String lang,
            String q, UUID categoryId, UUID supplierId,
            BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return productListFull(page, size, lang, q, categoryId, supplierId, minPrice, maxPrice,
                null, null, null, null, null, null, null, sort);
    }

    // Plan 300k: el listado pasó de stream-Java sobre 2000 productos en memoria
    // a una sola query SQL paginada con índices compuestos cubriendo todas las
    // combinaciones golpeadas (ver schema-v29-perf-composite-indexes.sql).
    // Esto baja el coste de ~250 ms (con N+1) a ~8 ms estables.
    private PageResponse<ProductSummaryView> productListFull(int page, int size, String lang,
            String q, UUID categoryId, UUID supplierId,
            BigDecimal minPrice, BigDecimal maxPrice,
            String shipFrom, Boolean freeShipping, Boolean selfPickup, Boolean hasVideo,
            Integer minRating, Integer inventoryMin, String certification, String sort) {

        int safeSize = Math.min(size, 100);
        Sort sortSpec = sortFor(sort);
        Pageable pageable = PageRequest.of(page, safeSize, sortSpec);

        String needle = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        String shipCc = shipFrom == null ? null : shipFrom.toUpperCase();
        BigDecimal minRatingBd = minRating == null ? null : BigDecimal.valueOf(minRating);

        Page<ProductEntity> raw = productRepository.searchStorefront(
                ProductStatus.ACTIVE, needle, categoryId, supplierId,
                minPrice, maxPrice, shipCc, freeShipping, selfPickup, hasVideo,
                minRatingBd, inventoryMin, pageable);

        // Filtro de certificaciones in-memory sobre la página (no se puede
        // expresar en SQL portable sin operadores de array Postgres-específicos).
        // Como ya hemos paginado a 100 items max, el coste es despreciable.
        List<ProductEntity> filtered;
        if (certification != null && !certification.isBlank()) {
            String certUp = certification.toUpperCase();
            filtered = raw.getContent().stream()
                    .filter(p -> p.getCertifications() != null && p.getCertifications().stream()
                            .anyMatch(c -> c != null && c.toUpperCase().contains(certUp)))
                    .toList();
        } else {
            filtered = raw.getContent();
        }

        List<ProductSummaryView> slice = filtered.stream()
                .map(p -> catalogService.toSummaryView(p, lang))
                .toList();
        Page<ProductSummaryView> pageObj = new PageImpl<>(slice, pageable, raw.getTotalElements());
        return PageResponse.from(pageObj);
    }

    /** Mapea el parámetro `sort` del frontend a una {@link Sort} de Spring Data. */
    private Sort sortFor(String sort) {
        return switch (sort == null ? "best_match" : sort) {
            case "price_asc"  -> Sort.by(Sort.Direction.ASC, "basePrice");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "basePrice");
            case "newest"     -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "sales", "lists" -> Sort.by(Sort.Direction.DESC, "monthlySales");
            case "rating"     -> Sort.by(Sort.Direction.DESC, "rating");
            case "inventory"  -> Sort.by(Sort.Direction.DESC, "inventoryCount");
            default           -> Sort.by(Sort.Direction.DESC, "trendScore");
        };
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    private boolean matchesNeedle(ProductEntity p, String needle) {
        if (p.getTitleZh() != null && p.getTitleZh().toLowerCase().contains(needle)) return true;
        if (p.getExternalId() != null && p.getExternalId().toLowerCase().contains(needle)) return true;
        if (p.getSlug() != null && p.getSlug().toLowerCase().contains(needle)) return true;
        return p.getTranslations() != null && p.getTranslations().stream()
                .anyMatch(t -> t.getTitle() != null && t.getTitle().toLowerCase().contains(needle));
    }

    private String translatedTitle(ProductEntity p, String lang) {
        if (p.getTranslations() == null) return null;
        return p.getTranslations().stream()
                .filter(t -> lang.equalsIgnoreCase(t.getLanguage()))
                .map(ProductTranslationEntity::getTitle).findFirst().orElse(null);
    }

    private int effectiveWeight(ProductEntity p) {
        if (p.getPackageWeightGrams() != null) return p.getPackageWeightGrams();
        if (p.getWeightGrams() != null) return p.getWeightGrams();
        return 500; // sane default 500g
    }

    private static String firstNonNull(String a, String b) { return a != null && !a.isBlank() ? a : b; }
    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static Instant nz(Instant v) { return v == null ? Instant.EPOCH : v; }

    private static String translatedName(CategoryEntity c, String lang) {
        return c.getTranslations().stream()
                .filter(t -> lang.equalsIgnoreCase(t.getLanguage()))
                .map(CategoryTranslationEntity::getName).findFirst()
                .orElse(c.getNameZh());
    }
}
