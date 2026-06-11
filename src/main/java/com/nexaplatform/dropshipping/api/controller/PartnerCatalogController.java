package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerCatalogApi;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.application.service.CatalogService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Partner-facing catalog API.
 *
 * Delegates the heavy filtering, language resolution and DTO mapping to
 * {@link StorefrontCatalogController} so partner and public expose the same
 * shapes — partners just hit a different URL prefix that requires JWT bearer
 * with the {@code catalog.read} scope.
 *
 * Why delegate via the bean instead of duplicating? Two reasons:
 *   - The filter logic (15+ params, sort modes, language fallbacks) is
 *     non-trivial; keeping a single source prevents drift between scopes.
 *   - Cross-cutting (rate-limit headers, OpenAPI types) is already configured
 *     for these handler methods.
 */
@RestController
@RequestMapping("/api/v1/partner/catalog")
@RequiredArgsConstructor
public class PartnerCatalogController implements PartnerCatalogApi {

    private final CatalogService catalogService;
    private final StorefrontCatalogController storefront;

    /* ============================ Products ============================ */

    @Override
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
        return storefront.list(page, size, lang, q, categoryId, supplierId, minPrice, maxPrice,
                shipFrom, freeShipping, selfPickup, hasVideo, minRating, inventoryMin, certification, sort);
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
    public ProductDetailView detail(String slug,
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
        return storefront.detailByExternal(source, externalId, lang);
    }

    /* ============================ Categories ============================ */

    @Override
    public List<CategoryView> categoriesFlat(String lang) {
        return storefront.categoriesFlat(lang);
    }

    @Override
    public List<CategoryView> categoriesTree(String lang) {
        return storefront.categoriesTree(lang);
    }

    @Override
    public CategoryView categoryDetail(String idOrSlug,
                                       String lang) {
        return storefront.categoryDetail(idOrSlug, lang);
    }

    @Override
    public List<CategoryView> categoryChildren(String idOrSlug,
                                               String lang) {
        return storefront.categoryChildren(idOrSlug, lang);
    }

    @Override
    public List<CategoryBreadcrumb> categoryBreadcrumb(String idOrSlug,
                                                       String lang) {
        return storefront.categoryBreadcrumb(idOrSlug, lang);
    }

    @Override
    public PageResponse<ProductSummaryView> productsByCategory(
            String idOrSlug,
            int page,
            int size,
            String lang,
            String sort) {
        return storefront.productsByCategory(idOrSlug, page, size, lang, sort);
    }

    /* ============================ Variants ============================ */

    @Override
    public List<VariantView> productVariants(UUID productId) {
        return storefront.variantsForProduct(productId);
    }

    @Override
    public VariantView variantDetail(UUID id) {
        return storefront.variantById(id);
    }

    @Override
    public VariantView variantBySku(UUID productId,
                                    String sku) {
        return storefront.variantBySku(productId, sku);
    }

    /* ============================ Suppliers ============================ */

    @Override
    public List<SupplierView> suppliers() {
        return storefront.suppliers();
    }

    @Override
    public SupplierView supplierDetail(UUID id) {
        return storefront.supplierDetail(id);
    }

    @Override
    public PageResponse<ProductSummaryView> productsBySupplier(
            UUID id,
            int page,
            int size,
            String lang,
            String sort) {
        return storefront.productsBySupplier(id, page, size, lang, sort);
    }

    // ProductStatus reference kept to make the import unambiguous in case
    // bestsellers() callers need to pass a different filter in the future.
    @SuppressWarnings("unused")
    private ProductStatus referenceForCompile() { return ProductStatus.ACTIVE; }
}
