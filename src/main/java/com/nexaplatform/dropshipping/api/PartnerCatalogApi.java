package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SupplierView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.VariantView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the partner-facing catalog resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Partner · Catalog", description = "Browse the NexaDrop catalog (requires scope catalog.read)")
@SecurityRequirement(name = "oauth2")
@SecurityRequirement(name = "bearer-jwt")
public interface PartnerCatalogApi {

    /* ============================ Products ============================ */

    @Operation(summary = "List products with full filters and sort", description = "Same filter and sort options as the public storefront, but scoped to authorised partners. "
            + "Supports search, category, supplier, price range, certifications, shipping origin, video, "
            + "rating minimum, inventory minimum, and `sort=best_match|price_asc|price_desc|newest|sales|rating|inventory`.")
    @GetMapping("/products")
    PageResponse<ProductSummaryView> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "en") String lang,
            @Parameter(description = "Free-text needle matched against title (any language), SKU and slug") @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) BigDecimal minPrice, @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "ISO-3166-1 alpha-2 origin country of the supplier") @RequestParam(required = false) String shipFrom,
            @RequestParam(required = false) Boolean freeShipping, @RequestParam(required = false) Boolean selfPickup,
            @RequestParam(required = false) Boolean hasVideo, @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer inventoryMin, @RequestParam(required = false) String certification,
            @RequestParam(required = false, defaultValue = "best_match") String sort);

    @Operation(summary = "Flat list of trending bestsellers, optionally narrowed to a category")
    @GetMapping("/bestsellers")
    PageResponse<ProductSummaryView> bestsellers(@RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Product detail by slug (includes variants, price tiers, images)")
    @GetMapping("/products/{slug}")
    ProductDetailView detail(@PathVariable String slug, @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Product detail by internal UUID")
    @GetMapping("/products/by-id/{id}")
    ProductDetailView detailById(@PathVariable UUID id, @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Product detail by external source (e.g., 1688) + external id")
    @GetMapping("/products/by-external/{source}/{externalId}")
    ProductDetailView detailByExternal(@PathVariable String source, @PathVariable String externalId,
            @RequestParam(defaultValue = "en") String lang);

    /* ============================ Categories ============================ */

    @Operation(summary = "Flat list of all categories with translated names")
    @GetMapping("/categories")
    List<CategoryView> categoriesFlat(@RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Category tree (parent → children) for navigation menus")
    @GetMapping("/categories/tree")
    List<CategoryView> categoriesTree(@RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Category detail by UUID or slug")
    @GetMapping("/categories/{idOrSlug}")
    CategoryView categoryDetail(@PathVariable String idOrSlug, @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Children of a category (one level deep)")
    @GetMapping("/categories/{idOrSlug}/children")
    List<CategoryView> categoryChildren(@PathVariable String idOrSlug, @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Breadcrumb trail from root to the requested category")
    @GetMapping("/categories/{idOrSlug}/breadcrumb")
    List<CategoryBreadcrumb> categoryBreadcrumb(@PathVariable String idOrSlug,
            @RequestParam(defaultValue = "en") String lang);

    @Operation(summary = "Products inside a category", description = "Convenience shortcut for `GET /products?categoryId=...`. Pagination and sort are honoured.")
    @GetMapping("/categories/{idOrSlug}/products")
    PageResponse<ProductSummaryView> productsByCategory(@PathVariable String idOrSlug,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "en") String lang, @RequestParam(required = false) String sort);

    /* ============================ Variants ============================ */

    @Operation(summary = "Variants of a product (color, size, etc.)", description = "Returns the full variant list for a product. Each entry includes its SKU, price, stock and "
            + "the option values that define it (e.g., color=red, size=L).")
    @GetMapping("/products/{productId}/variants")
    List<VariantView> productVariants(@PathVariable UUID productId);

    @Operation(summary = "Variant detail by id")
    @GetMapping("/variants/{id}")
    VariantView variantDetail(@PathVariable UUID id);

    @Operation(summary = "Resolve a variant by product + SKU (idempotent lookup for inbound orders)")
    @GetMapping("/products/{productId}/variants/by-sku/{sku}")
    VariantView variantBySku(@PathVariable UUID productId, @PathVariable String sku);

    /* ============================ Suppliers ============================ */

    @Operation(summary = "List active suppliers (their products are filterable via `supplierId`)")
    @GetMapping("/suppliers")
    List<SupplierView> suppliers();

    @Operation(summary = "Supplier detail")
    @GetMapping("/suppliers/{id}")
    SupplierView supplierDetail(@PathVariable UUID id);

    @Operation(summary = "Products of a single supplier")
    @GetMapping("/suppliers/{id}/products")
    PageResponse<ProductSummaryView> productsBySupplier(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "en") String lang, @RequestParam(defaultValue = "trending") String sort);
}
