package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.AnuncioBusFallidoView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CustomsAuditView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AddProductImageDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductSourceUrlDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminSubsidyBulkDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ReorderProductImagesDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.math.BigDecimal;
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

    @Operation(summary = "List products with paging and optional free-text (q) / status / category filter + sort")
    @GetMapping("/products")
    PageResponse<ProductSummaryView> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size, @RequestParam(defaultValue = "es") String lang,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Boolean verified,
            // Filtros de la tabla del panel. El COSTE va en CNY, que es como está guardado y lo que muestra
            // la columna «Precio» (el navegador la convierte para enseñarla, y deshace esa conversión antes
            // de mandar el filtro). Ventas y tendencia son mínimos, no rangos.
            @RequestParam(required = false) BigDecimal minCost, @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) Integer minSales, @RequestParam(required = false) BigDecimal minTrend);

    /**
     * Repaso del catálogo en busca de productos que la aduana no aceptaría.
     *
     * <p>Va ANTES de {@code /products/{id}} porque comparten prefijo: declarado después, Spring intentaría
     * interpretar «customs-gaps» como un identificador.
     */
    @Operation(summary = "Products missing mandatory customs data (EName, CName, HSCode, weight, value)")
    @GetMapping("/products/customs-gaps")
    CustomsAuditView customsGaps(@RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "500") int max);

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

    /**
     * Update en lote del recargo fijo por producto (surcharge_cny, 30-ago-2026): por producto, por
     * categoría o para todo el catálogo (productIds / categoryId / ninguno). Devuelve cuántos se tocaron.
     */
    @Operation(summary = "Set the product surcharge (CNY) in bulk: by product, by category or for all")
    @PutMapping("/products/surcharge")
    ResponseEntity<Map<String, Object>> bulkUpdateSurcharge(@Valid @RequestBody AdminSurchargeBulkDtoIn req);

    /**
     * Update en lote de las dos bolsas de subvención por producto (1-sep-2026): por producto, por
     * categoría o para todo el catálogo. Un importe ausente deja esa bolsa como estaba.
     */
    @Operation(summary = "Set the product subsidy bags (CNY) in bulk: by product, by category or for all")
    @PutMapping("/products/subsidy")
    ResponseEntity<Map<String, Object>> bulkUpdateSubsidy(@Valid @RequestBody AdminSubsidyBulkDtoIn req);

    @Operation(summary = "Update the supplier listing URL (1688/Alibaba) of a product. "
            + "Rejects any other domain or scheme with 422 PRODUCT_SOURCE_URL_INVALID.")
    @PutMapping("/products/{id}/source-url")
    ProductDetailView updateSourceUrl(@PathVariable UUID id,
            @Valid @RequestBody AdminProductSourceUrlDtoIn req,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Duplicate a product")
    @PostMapping("/products/{id}/duplicate")
    ProductDetailView duplicate(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    /* ============================ Reindex ============================ */

    @Operation(summary = "Kick off a full catalog reindex in the BACKGROUND (returns 202 immediately). "
            + "Long reindexes of large catalogs would otherwise die on the proxy/edge timeout.")
    @PostMapping("/reindex")
    ResponseEntity<Map<String, Object>> reindex();

    @Operation(summary = "Send a batch of already-stored images back to the mirror queue so they get "
            + "compressed. Only images saved before the compressor existed are picked, heaviest first. "
            + "Returns how many were requeued and how many remain, so it can be run batch by batch.")
    @PostMapping("/imagenes/comprimir-historico")
    ResponseEntity<Map<String, Object>> comprimirHistoricoDeImagenes(
            @RequestParam(defaultValue = "200") int limite);

    @Operation(summary = "How much of the image backlog is left to compress ({pendientes, enCola}), so the "
            + "panel can chain batches without piling them up")
    @GetMapping("/imagenes/comprimir-historico/estado")
    ResponseEntity<Map<String, Object>> estadoDeCompresionDeImagenes();

    @Operation(summary = "Status of the background reindex ({running, indexed}) so the panel can poll it")
    @GetMapping("/reindex/status")
    ResponseEntity<Map<String, Object>> reindexStatus();

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

    @Operation(summary = "Delete the explanation video of a product (clears video_url/has_video)")
    @DeleteMapping("/products/{id}/video")
    ResponseEntity<Void> deleteProductVideo(@PathVariable UUID id);

    @Operation(summary = "Reorder a product's gallery images (first becomes the main image)")
    @PutMapping("/products/{productId}/images/order")
    ResponseEntity<Void> reorderProductImages(
            @PathVariable UUID productId,
            @Valid @RequestBody ReorderProductImagesDtoIn req);

    /* ============================ Bulk import ============================ */

    @Operation(summary = "Create a single product manually")
    @PostMapping("/products/create")
    ResponseEntity<UUID> createProduct(@Valid @RequestBody BulkProductDtoIn req);

    @Operation(summary = "Delete a product (refused if it has orders)")
    @DeleteMapping("/products/{id}")
    ResponseEntity<Void> deleteProduct(@PathVariable UUID id);

    @Operation(summary = "Delete a single price tier of a product (by its min quantity)")
    @DeleteMapping("/products/{id}/price-tiers/{minQty}")
    ResponseEntity<Void> deletePriceTier(@PathVariable UUID id, @PathVariable int minQty);

    @Operation(summary = "Bulk-delete products by id (per-id error reporting; each refused if it has orders)")
    @PostMapping("/products/bulk-delete")
    ResponseEntity<Map<String, Object>> bulkDeleteProducts(@RequestBody List<UUID> ids);

    @Operation(summary = "Bulk-create products from a JSON array")
    @PostMapping("/products/bulk")
    ResponseEntity<BulkResultDtoOut> bulkProducts(@Valid @RequestBody List<BulkProductDtoIn> rows);

    @Operation(summary = "Export products in a 1-based range as the same bulk JSON shape (re-importable). "
            + "Optional createdFrom/createdTo (yyyy-MM-dd) filter by upload date (ingestedAt), and optional "
            + "verified filter (true = only certified, false = only pending). All filters combine.")
    @GetMapping("/products/export")
    ResponseEntity<List<BulkProductDtoIn>> exportProducts(@RequestParam(defaultValue = "1") int from,
            @RequestParam(defaultValue = "1000") int to,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Boolean verified,
            // Los MISMOS filtros que la lista del panel: sin ellos, filtrar la lista a treinta productos
            // y abrir «Exportar» ofrecía los nueve mil, porque eran dos ideas distintas de «el catálogo».
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minCost,
            @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) Integer minSales,
            @RequestParam(required = false) BigDecimal minTrend);

    @Operation(summary = "Total product count (to compute export segments); optional createdFrom/createdTo and "
            + "verified filters, combined the same way the export applies them")
    @GetMapping("/products/export/count")
    ResponseEntity<Map<String, Long>> exportCount(
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Boolean verified,
            // Los MISMOS filtros que la lista del panel: sin ellos, filtrar la lista a treinta productos
            // y abrir «Exportar» ofrecía los nueve mil, porque eran dos ideas distintas de «el catálogo».
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minCost,
            @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) Integer minSales,
            @RequestParam(required = false) BigDecimal minTrend);

    @Operation(summary = "Export ONE product as the bulk JSON shape (to edit as JSON and re-import with upsert)")
    @GetMapping("/products/{id}/export")
    ResponseEntity<BulkProductDtoIn> exportProduct(@PathVariable UUID id);

    @Operation(summary = "Stream ALL products as NDJSON (one product per line), batched with bounded memory. "
            + "Scales to millions: the server keyset-paginates and flushes each batch instead of buffering everything.")
    @GetMapping(value = "/products/export/ndjson", produces = "application/x-ndjson")
    ResponseEntity<StreamingResponseBody> exportProductsNdjson(@RequestParam(defaultValue = "200") int batch,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) Boolean verified,
            // Los mismos filtros que los otros dos caminos: el volcado completo y los tramos tienen que
            // acotar igual, o el total que se ofrece no cuadra con lo que se descarga.
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) BigDecimal minCost,
            @RequestParam(required = false) BigDecimal maxCost,
            @RequestParam(required = false) Integer minSales,
            @RequestParam(required = false) BigDecimal minTrend);

    @Operation(summary = "Import products from an NDJSON body (one product per line), processed in batches with "
            + "bounded memory. The request body is read as a stream and never fully loaded into memory.")
    @PostMapping(value = "/products/import/ndjson", consumes = "application/x-ndjson")
    ResponseEntity<BulkResultDtoOut> importProductsNdjson(HttpServletRequest request,
            @RequestParam(defaultValue = "200") int batch);

    @Operation(summary = "Products whose announcement to the catalogue bus was given up on. Certifying a product "
            + "answers immediately because the announcement is deferred, so a failure no longer fits in that "
            + "response: this is where the admin panel sees it.")
    @GetMapping("/bus/anuncios-fallidos")
    ResponseEntity<List<AnuncioBusFallidoView>> anunciosAlBusFallidos();

    @Operation(summary = "Put the given-up announcements back in the queue and reset their attempt counter")
    @PostMapping("/bus/anuncios-fallidos/reintentar")
    ResponseEntity<Map<String, Integer>> reintentarAnunciosAlBusFallidos();

    @Operation(summary = "Bulk-create categories from a JSON array")
    @PostMapping("/categories/bulk")
    ResponseEntity<BulkResultDtoOut> bulkCategories(@Valid @RequestBody List<BulkCategoryDtoIn> rows);
}
