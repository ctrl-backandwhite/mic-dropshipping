package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PartnerCatalogApi;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
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
 * <p>Exposes the same shapes as the public storefront — partners just hit a different
 * URL prefix that requires a JWT bearer with the {@code catalog.read} scope. The
 * heavy filtering, language resolution and view building now live in the shared
 * {@link CatalogUseCase} (priced product reads) and {@link CatalogStorefrontReadService}
 * (storefront view projections), so this controller no longer reaches into the
 * {@code StorefrontCatalogController} bean.
 */
@RestController
@RequestMapping("/api/v1/partner/catalog")
@RequiredArgsConstructor
public class PartnerCatalogController implements PartnerCatalogApi {

    private final CatalogUseCase catalogUseCase;
    private final CatalogStorefrontReadService storefrontRead;

    /* ============================ Products ============================ */

    @Override
    public PageResponse<ProductSummaryView> list(int page, int size, String lang, String q, UUID categoryId,
            UUID supplierId, BigDecimal minPrice, BigDecimal maxPrice, String shipFrom, Boolean freeShipping,
            Boolean selfPickup, Boolean hasVideo, Integer minRating, Integer inventoryMin, String certification,
            String sort) {
        return storefrontRead.productListFull(page, size, lang, q, categoryId, supplierId, minPrice, maxPrice, shipFrom,
                freeShipping, selfPickup, hasVideo, minRating, inventoryMin, certification, sort);
    }

    @Override
    public PageResponse<ProductSummaryView> bestsellers(UUID categoryId, int page, int size, String lang) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return PageResponse.from(catalogUseCase.listBestsellers(categoryId, pageable, lang));
    }

    @Override
    public ProductDetailView detail(String slug, String lang) {
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

    /* ============================ Categories ============================ */

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

    /* ============================ Variants ============================ */

    @Override
    public List<VariantView> productVariants(UUID productId) {
        return storefrontRead.variantsForProduct(productId);
    }

    @Override
    public VariantView variantDetail(UUID id) {
        return storefrontRead.variantById(id);
    }

    @Override
    public VariantView variantBySku(UUID productId, String sku) {
        return storefrontRead.variantBySku(productId, sku);
    }

    /* ============================ Suppliers ============================ */

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
}
