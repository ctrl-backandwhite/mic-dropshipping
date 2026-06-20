package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
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

    BigDecimal computeTrendScore(ProductEntity p);

    /* ============ Products: read ============ */

    Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language);

    Page<ProductSummaryView> listProductsForAdmin(String status, UUID categoryId, int page, int size, String language,
            String sort);

    /** Reindexes every product into OpenSearch; returns the number indexed. */
    int reindexAllProducts();

    /** DROP-679: rellena el SEO (meta_title/meta_description) faltante de productos ya activos. */
    int backfillMissingSeo();

    /** Deriva los ejes/valores de variación faltantes desde las variantes (productos sin selector). */
    int backfillVariantAxes();

    /* ============ DROP-677: mapeo categorías 1688 → interna ============ */

    UUID upsertCategory1688Mapping(String external1688Id, String external1688Name, UUID categoryId);

    List<Category1688MappingDtoOut> listCategory1688Mappings();

    void deleteCategory1688Mapping(UUID id);

    /* ============ DROP-670: esquema de atributos por categoría ============ */

    List<CategoryAttributeSchemaDtoOut> listCategoryAttributeSchema(
            UUID categoryId);

    UUID upsertCategoryAttributeSchema(UUID categoryId, String attrKey, String label, boolean required, int position);

    void deleteCategoryAttributeSchema(UUID id);

    /** Lists a product's variants with their RAW stored price (admin manage view, no margin/currency). */
    List<VariantView> listVariantsForAdmin(UUID productId);

    /** Creates a variant on a product and reindexes it. */
    VariantView createVariant(UUID productId, AdminVariantUpsertDtoIn req);

    /** Updates an existing variant and reindexes its product. */
    VariantView updateVariant(UUID variantId, AdminVariantUpsertDtoIn req);

    /** Partial update: sets only the variant's price (en moneda canónica) sin tocar el resto. */
    VariantView updateVariantPrice(UUID variantId, BigDecimal price);

    /** Renombra la etiqueta visible de un valor de variación (p.ej. un color) y reindexa el producto. */
    void renameVariantValue(UUID valueId, String label);

    /** DROP-674: fija la imagen real de un valor de variación (p.ej. la foto de un color). Vacío la elimina. */
    void setVariantValueImage(UUID valueId, String imageUrl);

    /** Fija/actualiza la traducción de un valor de variación para un idioma (valor vacío la elimina). */
    void setVariantValueTranslation(UUID valueId, String language, String value);

    /** Deletes a variant and reindexes its product. */
    void deleteVariant(UUID variantId);

    /** Adds an image (by URL) to a product's gallery. */
    ProductImageView addProductImage(UUID productId, String url,
            String role);

    /** Removes a product image and reindexes its product. */
    void deleteProductImage(UUID imageId);

    /** Bulk-creates products from friendly JSON rows; returns created/failed counts and errors. */
    BulkResultDtoOut bulkCreateProducts(
            List<BulkProductDtoIn> rows);

    /**
     * Exports products in the given 1-based inclusive range (ordered deterministically by id) as the same
     * {@link BulkProductDtoIn} JSON shape used to create them, so the result can be re-imported. Lets the
     * admin export the catalog in fixed segments (1-1000, 1001-2000, …).
     */
    List<BulkProductDtoIn> exportProducts(int from, int to);

    /** Total number of products (used to compute the export segments). */
    long countProducts();

    /** Creates a single product manually from a friendly row; returns the new product id. */
    UUID createProductManual(BulkProductDtoIn req);

    /** Permanently deletes a product and its catalog children (refused if it has orders). */
    void deleteProduct(UUID id);

    /** Bulk-creates categories from friendly JSON rows. */
    BulkResultDtoOut bulkCreateCategories(
            List<BulkCategoryDtoIn> rows);

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
