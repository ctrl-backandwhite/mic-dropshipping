package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.AttributeKeyView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.AttributeView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.HistoryPoint;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.HomeSectionsResponse;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImageSearchRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImageSearchResult;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImportUrlRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ImportUrlResponse;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.MarginEstimate;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingQuoteItem;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingQuoteRequest;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingRateView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.ShippingZoneView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SpecificationView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SuggestionView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the public Storefront Catalog resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Storefront Catalog")
public interface StorefrontCatalogApi {

    /* =========================== CATEGORIES =========================== */

    @Operation(summary = "List root categories (flat)")
    @GetMapping("/categories")
    List<CategoryView> categoriesFlat(@RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List categories as a tree")
    @GetMapping("/categories/tree")
    List<CategoryView> categoriesTree(@RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Get a category by id or slug")
    @GetMapping("/categories/{idOrSlug}")
    CategoryView categoryDetail(@PathVariable String idOrSlug, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List the children of a category")
    @GetMapping("/categories/{idOrSlug}/children")
    List<CategoryView> categoryChildren(@PathVariable String idOrSlug, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Get the breadcrumb path of a category")
    @GetMapping("/categories/{idOrSlug}/breadcrumb")
    List<CategoryBreadcrumb> categoryBreadcrumb(@PathVariable String idOrSlug,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List products in a category")
    @GetMapping("/categories/{idOrSlug}/products")
    PageResponse<ProductSummaryView> productsByCategory(@PathVariable String idOrSlug,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "es") String lang, @RequestParam(defaultValue = "trending") String sort);

    /* =========================== SUPPLIERS =========================== */

    @Operation(summary = "List all suppliers")
    @GetMapping("/suppliers")
    List<SupplierView> suppliers();

    @Operation(summary = "Get a supplier by id")
    @GetMapping("/suppliers/{id}")
    SupplierView supplierDetail(@PathVariable UUID id);

    @Operation(summary = "List products of a supplier")
    @GetMapping("/suppliers/{id}/products")
    PageResponse<ProductSummaryView> productsBySupplier(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "es") String lang, @RequestParam(defaultValue = "trending") String sort);

    /* =========================== PRODUCTS =========================== */

    @Operation(summary = "List/search products with filters")
    @GetMapping("/products")
    PageResponse<ProductSummaryView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(required = false) String q, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice, @RequestParam(required = false) String shipFrom,
            @RequestParam(required = false) Boolean freeShipping, @RequestParam(required = false) Boolean selfPickup,
            @RequestParam(required = false) Boolean hasVideo, @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer inventoryMin, @RequestParam(required = false) String certification,
            @RequestParam(required = false, defaultValue = "best_match") String sort,
            @RequestParam(required = false) Boolean verified);

    @Operation(summary = "Get a product detail by slug")
    @GetMapping("/products/{slug}")
    ProductDetailView detailBySlug(@PathVariable String slug, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Get a product detail by id")
    @GetMapping("/products/by-id/{id}")
    ProductDetailView detailById(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Get a product detail by external source and id")
    @GetMapping("/products/by-external/{source}/{externalId}")
    ProductDetailView detailByExternal(@PathVariable String source, @PathVariable String externalId,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List bestseller products")
    @GetMapping("/bestsellers")
    PageResponse<ProductSummaryView> bestsellers(@RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List trending products")
    @GetMapping("/products/trending")
    PageResponse<ProductSummaryView> trending(@RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List newest products")
    @GetMapping("/products/newest")
    PageResponse<ProductSummaryView> newest(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List products related to a product")
    @GetMapping("/products/{id}/related")
    List<ProductSummaryView> relatedProducts(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(defaultValue = "8") int limit);

    @Operation(summary = "List the specifications of a product")
    @GetMapping("/products/{id}/specifications")
    List<SpecificationView> specifications(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "List the attributes of a product")
    @GetMapping("/products/{id}/attributes")
    List<AttributeView> attributes(@PathVariable UUID id,
            @RequestParam(name = "lang", defaultValue = "es") String lang);

    @Operation(summary = "List the tags of a product")
    @GetMapping("/products/{id}/tags")
    List<String> tags(@PathVariable UUID id);

    @Operation(summary = "List the images of a product")
    @GetMapping("/products/{id}/images")
    List<CatalogImageDtoOut> images(@PathVariable UUID id);

    @Operation(summary = "List the price tiers of a product")
    @GetMapping("/products/{id}/price-tiers")
    List<CatalogPriceTierDtoOut> priceTiers(@PathVariable UUID id);

    @Operation(summary = "Suggest products for an autocomplete query")
    @GetMapping("/products/suggest")
    List<SuggestionView> suggest(@RequestParam String q, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(defaultValue = "8") int limit);

    /* =========================== VARIANTS =========================== */

    @Operation(summary = "List the variants of a product")
    @GetMapping("/products/{id}/variants")
    List<VariantView> variantsForProduct(@PathVariable UUID id);

    @Operation(summary = "Get a variant by id")
    @GetMapping("/variants/{id}")
    VariantView variantById(@PathVariable UUID id);

    @Operation(summary = "Get a product variant by SKU")
    @GetMapping("/products/{productId}/variants/by-sku/{sku}")
    VariantView variantBySku(@PathVariable UUID productId, @PathVariable String sku);

    @Operation(summary = "Match product variants by selected options")
    @PostMapping("/products/{productId}/variants/match")
    List<VariantView> variantMatch(@PathVariable UUID productId, @RequestBody Map<String, String> options);

    /* =========================== ATTRIBUTES (taxonomy) =========================== */

    @Operation(summary = "List attribute keys with usage counts")
    @GetMapping("/attributes/keys")
    List<AttributeKeyView> attributeKeys();

    @Operation(summary = "List the distinct values of an attribute key")
    @GetMapping("/attributes/{key}/values")
    List<String> attributeValues(@PathVariable String key);

    /* =========================== TAGS =========================== */

    @Operation(summary = "List all distinct tags")
    @GetMapping("/tags")
    List<String> allTags();

    @Operation(summary = "List products by tag")
    @GetMapping("/tags/{tag}/products")
    List<ProductSummaryView> productsByTag(@PathVariable String tag, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(defaultValue = "24") int limit);

    /* =========================== SHIPPING =========================== */

    @Operation(summary = "List the active shipping zones of a supplier")
    @GetMapping("/shipping/zones")
    List<ShippingZoneView> shippingZones(@RequestParam UUID supplierId);

    @Operation(summary = "List the shipping rates of a supplier for a country")
    @GetMapping("/shipping/rates")
    List<ShippingRateView> shippingRates(@RequestParam UUID supplierId, @RequestParam String country);

    @Operation(summary = "Quote shipping options for a product to a country")
    @PostMapping("/shipping/quote")
    List<ShippingQuoteItem> shippingQuote(@RequestBody ShippingQuoteRequest req);

    /* =========================== HOME SECTIONS (DROP-20) =========================== */

    @Operation(summary = "Get the homepage sections and hot categories")
    @GetMapping("/home/sections")
    HomeSectionsResponse homeSections(@RequestParam(defaultValue = "es") String lang,
            @RequestParam(defaultValue = "8") int perSection);

    /* =========================== IMPORT BY URL (DROP-15) =========================== */

    @Operation(summary = "Resolve a 1688/taobao/aliexpress/ebay URL against the catalog")
    @PostMapping("/products/import-url")
    ImportUrlResponse importByUrl(@RequestBody ImportUrlRequest req, @RequestParam(defaultValue = "es") String lang);

    /* =========================== IMAGE SEARCH MOCK (DROP-16) =========================== */

    @Operation(summary = "Search products by image")
    @PostMapping("/products/search-by-image")
    List<ImageSearchResult> searchByImage(@RequestBody ImageSearchRequest req,
            @RequestParam(defaultValue = "es") String lang);

    /* =========================== PRICE/STOCK HISTORY (DROP-25) =========================== */

    @Operation(summary = "Get the price/stock history of a product")
    @GetMapping("/products/{id}/price-history")
    List<HistoryPoint> priceHistory(@PathVariable UUID id, @RequestParam(defaultValue = "90") int days);

    /* =========================== MARGIN ESTIMATE (DROP-24) =========================== */

    @Operation(summary = "Estimate the dropshipping margin for a product")
    @GetMapping("/products/{id}/margin-estimate")
    MarginEstimate marginEstimate(@PathVariable UUID id, @RequestParam(defaultValue = "ES") String country,
            @RequestParam(defaultValue = "1") int quantity,
            @RequestParam(required = false) UUID variantId);
}
