package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AddProductImageDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReindexResultDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Catalog resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Catalog")
public interface AdminCatalogApi {

    @Operation(summary = "Create or update a supplier")
    @PostMapping("/suppliers")
    ResponseEntity<UUID> upsertSupplier(@Valid @RequestBody IngestSupplierRequest req);

    @Operation(summary = "Create a new category (rejects duplicate slug with 409)")
    @PostMapping("/categories")
    ResponseEntity<UUID> upsertCategory(@Valid @RequestBody IngestCategoryRequest req);

    @Operation(summary = "Create or update a product")
    @PostMapping("/products")
    ResponseEntity<UUID> upsertProduct(@Valid @RequestBody IngestProductRequest req);

    @Operation(summary = "List products with paging and optional status/category filter + sort")
    @GetMapping("/products")
    PageResponse<ProductSummaryView> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(required = false) String sort);

    @Operation(summary = "Get product detail by id")
    @GetMapping("/products/{id}")
    ProductDetailView detail(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Update a product status")
    @PutMapping("/products/{id}/status")
    ResponseEntity<Void> updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateProductStatusRequest req);

    @Operation(summary = "Quick-edit a product")
    @PutMapping("/products/{id}")
    ProductDetailView quickEdit(@PathVariable UUID id,
            @Valid @RequestBody AdminProductQuickEditDtoIn req,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Duplicate a product")
    @PostMapping("/products/{id}/duplicate")
    ProductDetailView duplicate(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    /* ============================ Reindex ============================ */

    @Operation(summary = "Reindex the whole catalog into OpenSearch")
    @PostMapping("/reindex")
    ResponseEntity<ReindexResultDtoOut> reindex();

    /* ============================ Variants ============================ */

    @Operation(summary = "List a product's variants with their raw stored price (admin)")
    @GetMapping("/products/{productId}/variants")
    ResponseEntity<List<VariantView>> listVariants(@PathVariable UUID productId);

    @Operation(summary = "Create a variant on a product")
    @PostMapping("/products/{productId}/variants")
    ResponseEntity<VariantView> createVariant(@PathVariable UUID productId,
            @Valid @RequestBody AdminVariantUpsertDtoIn req);

    @Operation(summary = "Update a variant")
    @PutMapping("/variants/{id}")
    ResponseEntity<VariantView> updateVariant(@PathVariable UUID id, @Valid @RequestBody AdminVariantUpsertDtoIn req);

    @Operation(summary = "Delete a variant")
    @DeleteMapping("/variants/{id}")
    ResponseEntity<Void> deleteVariant(@PathVariable UUID id);

    /* ============================ Images (by URL) ============================ */

    @Operation(summary = "Add an image (by URL) to a product's gallery")
    @PostMapping("/products/{productId}/images")
    ResponseEntity<ProductImageView> addProductImage(
            @PathVariable UUID productId,
            @Valid @RequestBody AddProductImageDtoIn req);

    @Operation(summary = "Delete a product image")
    @DeleteMapping("/products/images/{imageId}")
    ResponseEntity<Void> deleteProductImage(@PathVariable UUID imageId);

    /* ============================ Bulk import ============================ */

    @Operation(summary = "Create a single product manually")
    @PostMapping("/products/create")
    ResponseEntity<UUID> createProduct(@Valid @RequestBody BulkProductDtoIn req);

    @Operation(summary = "Delete a product (refused if it has orders)")
    @DeleteMapping("/products/{id}")
    ResponseEntity<Void> deleteProduct(@PathVariable UUID id);

    @Operation(summary = "Bulk-delete products by id (per-id error reporting; each refused if it has orders)")
    @PostMapping("/products/bulk-delete")
    ResponseEntity<Map<String, Object>> bulkDeleteProducts(@RequestBody List<UUID> ids);

    @Operation(summary = "Bulk-create products from a JSON array")
    @PostMapping("/products/bulk")
    ResponseEntity<BulkResultDtoOut> bulkProducts(@Valid @RequestBody List<BulkProductDtoIn> rows);

    @Operation(summary = "Export products in a 1-based range as the same bulk JSON shape (re-importable)")
    @GetMapping("/products/export")
    ResponseEntity<List<BulkProductDtoIn>> exportProducts(@RequestParam(defaultValue = "1") int from,
            @RequestParam(defaultValue = "1000") int to);

    @Operation(summary = "Total product count (to compute export segments)")
    @GetMapping("/products/export/count")
    ResponseEntity<Map<String, Long>> exportCount();

    @Operation(summary = "Bulk-create categories from a JSON array")
    @PostMapping("/categories/bulk")
    ResponseEntity<BulkResultDtoOut> bulkCategories(@Valid @RequestBody List<BulkCategoryDtoIn> rows);
}
