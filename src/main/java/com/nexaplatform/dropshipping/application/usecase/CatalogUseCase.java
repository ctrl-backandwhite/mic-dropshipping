package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Catalog cluster (aggregate root {@link Product}). Absorbs
 * the logic that used to live in {@code CatalogService} and in the catalog
 * controllers, operating on the {@link Product} domain model and the catalog DTOs.
 * The three catalog controllers depend on this port (the partner controller no
 * longer reaches into the storefront controller).
 */
public interface CatalogUseCase {

    /* ============ Ingest (admin) ============ */

    SupplierEntity upsertSupplier(IngestSupplierRequest req);

    CategoryEntity createCategoryRejectingDuplicateSlug(IngestCategoryRequest req);

    CategoryEntity upsertCategory(IngestCategoryRequest req);

    ProductEntity upsertProduct(IngestProductRequest req);

    java.math.BigDecimal computeTrendScore(ProductEntity p);

    /* ============ Products: read ============ */

    Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language);

    Page<ProductSummaryView> listProductsForAdmin(String status, int page, int size, String language);

    ProductSummaryView toSummaryView(Product product, String language);

    Product getProductModelById(UUID id);

    Product getProductModelBySlug(String slug);

    ProductDetailView getProductBySlug(String slug, String language);

    ProductDetailView getProductById(UUID id, String language);

    ProductDetailView getProductByExternal(String source, String externalId, String language);

    Page<ProductSummaryView> listBestsellers(UUID categoryId, Pageable pageable, String language);

    List<CatalogImageDtoOut> listProductImages(UUID productId);

    List<CatalogPriceTierDtoOut> listProductPriceTiers(UUID productId);

    /* ============ Products: mutate (admin) ============ */

    void updateStatus(UUID id, String status);

    void updateStatus(UUID id, ProductStatus status);

    ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang);

    ProductDetailView duplicateProduct(UUID id, String lang);
}
