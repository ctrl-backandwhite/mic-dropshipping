package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminCatalogApi;
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
import com.nexaplatform.dropshipping.api.dto.in.ReorderProductImagesDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReindexResultDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Catalog controller. Pure implementation of {@link AdminCatalogApi}: every
 * endpoint delegates to the {@link CatalogUseCase}; no business logic, no manual
 * mapping. Routes and status codes are inherited from the API interface.
 */
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController implements AdminCatalogApi {

    private static final int MAX_BATCH = 1000;

    private final CatalogUseCase catalogUseCase;
    private final ObjectMapper objectMapper;

    @Override
    public ResponseEntity<UUID> upsertSupplier(IngestSupplierRequest req) {
        return ResponseEntity.ok(catalogUseCase.upsertSupplier(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertCategory(IngestCategoryRequest req) {
        return ResponseEntity.ok(catalogUseCase.createCategoryRejectingDuplicateSlug(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertProduct(IngestProductRequest req) {
        return ResponseEntity.ok(catalogUseCase.upsertProduct(req).getId());
    }

    @Override
    public PageResponse<ProductSummaryView> list(String status, UUID categoryId, String q, int page, int size,
            String lang, String sort, Boolean verified) {
        return PageResponse
                .from(catalogUseCase.listProductsForAdmin(status, categoryId, q, page, size, lang, sort, verified));
    }

    @Override
    public ProductDetailView detail(UUID id, String lang) {
        return catalogUseCase.getProductById(id, lang);
    }

    @Override
    public ResponseEntity<Void> updateStatus(UUID id, UpdateProductStatusRequest req) {
        catalogUseCase.updateStatus(id, req.status());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang) {
        return catalogUseCase.quickEdit(id, req, lang);
    }

    @Override
    public ProductDetailView duplicate(UUID id, String lang) {
        return catalogUseCase.duplicateProduct(id, lang);
    }

    @Override
    public ResponseEntity<ReindexResultDtoOut> reindex() {
        return ResponseEntity.ok(new ReindexResultDtoOut(catalogUseCase.reindexAllProducts()));
    }

    @Override
    public ResponseEntity<List<VariantView>> listVariants(UUID productId) {
        return ResponseEntity.ok(catalogUseCase.listVariantsForAdmin(productId));
    }

    @Override
    public ResponseEntity<VariantView> createVariant(UUID productId, AdminVariantUpsertDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.createVariant(productId, req));
    }

    @Override
    public ResponseEntity<VariantView> updateVariant(UUID id, AdminVariantUpsertDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.updateVariant(id, req));
    }

    /** Edición inline del precio por variante (moneda canónica), sin tocar el resto de campos. */
    @PutMapping("/variants/{id}/price")
    public ResponseEntity<VariantView> updateVariantPrice(@PathVariable UUID id,
            @RequestBody Map<String, BigDecimal> body) {
        return ResponseEntity.ok(catalogUseCase.updateVariantPrice(id, body.get("price")));
    }

    /** Renombra la etiqueta visible de un valor de variación (p.ej. un color/estampado). */
    @PutMapping("/variant-values/{id}/label")
    public ResponseEntity<Void> renameVariantValue(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        catalogUseCase.renameVariantValue(id, body.get("value"));
        return ResponseEntity.noContent().build();
    }

    /** DROP-674: fija la imagen real de un valor de variación (p.ej. la foto de un color). */
    @PutMapping("/variant-values/{id}/image")
    public ResponseEntity<Void> setVariantValueImage(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        catalogUseCase.setVariantValueImage(id, body.get("imageUrl"));
        return ResponseEntity.noContent().build();
    }

    /** Elimina un valor de variación (p.ej. un color/estampado) y todas las combinaciones que lo usan. */
    @DeleteMapping("/variant-values/{id}")
    public ResponseEntity<Void> deleteVariantValue(@PathVariable UUID id) {
        catalogUseCase.deleteVariantValue(id);
        return ResponseEntity.noContent().build();
    }

    /** Traducción por idioma de un valor de variación (color/talla) — valor vacío la elimina. */
    @PutMapping("/variant-values/{id}/translation")
    public ResponseEntity<Void> setVariantValueTranslation(@PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        catalogUseCase.setVariantValueTranslation(id, body.get("language"), body.get("value"));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteVariant(UUID id) {
        catalogUseCase.deleteVariant(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProductImageView> addProductImage(
            UUID productId, AddProductImageDtoIn req) {
        return new ResponseEntity<>(catalogUseCase.addProductImage(productId, req.getUrl(), req.getRole()),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<Void> deleteProductImage(UUID imageId) {
        catalogUseCase.deleteProductImage(imageId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteProductVideo(UUID id) {
        catalogUseCase.deleteProductVideo(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reorderProductImages(UUID productId, ReorderProductImagesDtoIn req) {
        catalogUseCase.reorderProductImages(productId, req.getImageIds());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UUID> createProduct(BulkProductDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.createProductManual(req));
    }

    @Override
    public ResponseEntity<Void> deleteProduct(UUID id) {
        catalogUseCase.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deletePriceTier(UUID id, int minQty) {
        catalogUseCase.deletePriceTier(id, minQty);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> bulkDeleteProducts(List<UUID> ids) {
        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (UUID id : ids) {
            try {
                catalogUseCase.deleteProduct(id); // per-id tx + cache evict; refused if it has orders
                deleted++;
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ErrorMessages.humanize(ex));
            }
        }
        return ResponseEntity.ok(Map.of("deleted", deleted, "failed", errors.size(), "errors", errors));
    }

    /** Bulk publish/pause/archive the selected products (sets status: ACTIVE/PAUSED/ARCHIVED). */
    @PutMapping("/products/bulk-status")
    public ResponseEntity<Map<String, Object>> bulkProductStatus(@RequestBody BulkStatusRequest req) {
        int succeeded = 0;
        List<String> errors = new ArrayList<>();
        for (UUID id : req.ids()) {
            try {
                catalogUseCase.updateStatus(id, req.status());
                succeeded++;
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ErrorMessages.humanize(ex));
            }
        }
        return ResponseEntity.ok(Map.of("succeeded", succeeded, "failed", errors.size(), "errors", errors));
    }

    public record BulkStatusRequest(List<UUID> ids, String status) {
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> bulkProducts(List<BulkProductDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateProducts(rows));
    }

    @Override
    public ResponseEntity<List<BulkProductDtoIn>> exportProducts(int from, int to) {
        return ResponseEntity.ok(catalogUseCase.exportProducts(from, to));
    }

    @Override
    public ResponseEntity<Map<String, Long>> exportCount() {
        return ResponseEntity.ok(Map.of("count", catalogUseCase.countProducts()));
    }

    @Override
    public ResponseEntity<BulkProductDtoIn> exportProduct(UUID id) {
        return ResponseEntity.ok(catalogUseCase.exportProduct(id));
    }

    @Override
    public ResponseEntity<StreamingResponseBody> exportProductsNdjson(int batch) {
        int safeBatch = Math.min(Math.max(batch, 1), MAX_BATCH);
        // Stream one product per line; keyset-paginate and flush each batch so memory stays bounded to a
        // single page regardless of the total number of products (scales to millions).
        StreamingResponseBody body = out -> {
            UUID after = null;
            while (true) {
                CatalogUseCase.ProductExportBatch page = catalogUseCase.exportBatchAfter(after, safeBatch);
                if (page.items().isEmpty()) {
                    break;
                }
                for (BulkProductDtoIn dto : page.items()) {
                    out.write(objectMapper.writeValueAsBytes(dto));
                    out.write('\n');
                }
                out.flush();
                if (page.items().size() < safeBatch) {
                    break;
                }
                after = page.lastId();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-export.ndjson\"")
                .body(body);
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> importProductsNdjson(HttpServletRequest request, int batch) {
        int safeBatch = Math.min(Math.max(batch, 1), MAX_BATCH);
        NdjsonImportAccumulator acc = new NdjsonImportAccumulator();
        List<BulkProductDtoIn> buffer = new ArrayList<>(safeBatch);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    buffer.add(objectMapper.readValue(line, BulkProductDtoIn.class));
                } catch (Exception ex) {
                    acc.recordParseError(ex.getMessage());
                    continue;
                }
                if (buffer.size() >= safeBatch) {
                    acc.merge(catalogUseCase.bulkCreateProducts(buffer));
                    buffer.clear();
                }
            }
        } catch (java.io.IOException ex) {
            throw new BusinessException("No se pudo leer el cuerpo NDJSON de importación");
        }
        if (!buffer.isEmpty()) {
            acc.merge(catalogUseCase.bulkCreateProducts(buffer));
        }
        return ResponseEntity.ok(acc.toResult());
    }

    /** Accumulates the per-batch results of an NDJSON import while keeping the error list bounded. */
    private static final class NdjsonImportAccumulator {
        private static final int MAX_ERRORS = 100;
        private int created;
        private int failed;
        private final List<String> errors = new ArrayList<>();

        void recordParseError(String message) {
            failed++;
            addError("parse: " + message);
        }

        void merge(BulkResultDtoOut batchResult) {
            created += batchResult.getCreated();
            failed += batchResult.getFailed();
            if (batchResult.getErrors() != null) {
                for (String error : batchResult.getErrors()) {
                    addError(error);
                }
            }
        }

        private void addError(String error) {
            if (errors.size() < MAX_ERRORS) {
                errors.add(error);
            }
        }

        BulkResultDtoOut toResult() {
            return new BulkResultDtoOut(created, failed, errors);
        }
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> bulkCategories(List<BulkCategoryDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateCategories(rows));
    }

    /* ===================== DROP-677: mapeo categorías 1688 → interna ===================== */

    @GetMapping("/category-1688-mappings")
    public ResponseEntity<List<Category1688MappingDtoOut>> listCategory1688Mappings() {
        return ResponseEntity.ok(catalogUseCase.listCategory1688Mappings());
    }

    @PostMapping("/category-1688-mappings")
    public ResponseEntity<UUID> upsertCategory1688Mapping(@RequestBody Map<String, String> body) {
        UUID categoryId = UUID.fromString(body.get("categoryId"));
        UUID id = catalogUseCase.upsertCategory1688Mapping(body.get("external1688Id"), body.get("external1688Name"),
                categoryId);
        return ResponseEntity.ok(id);
    }

    @DeleteMapping("/category-1688-mappings/{id}")
    public ResponseEntity<Void> deleteCategory1688Mapping(@PathVariable UUID id) {
        catalogUseCase.deleteCategory1688Mapping(id);
        return ResponseEntity.noContent().build();
    }

    /* ===================== DROP-670: esquema de atributos por categoría ===================== */

    @GetMapping("/categories/{categoryId}/attribute-schema")
    public ResponseEntity<List<CategoryAttributeSchemaDtoOut>> listCategoryAttributeSchema(
            @PathVariable UUID categoryId) {
        return ResponseEntity.ok(catalogUseCase.listCategoryAttributeSchema(categoryId));
    }

    @PostMapping("/categories/{categoryId}/attribute-schema")
    public ResponseEntity<UUID> upsertCategoryAttributeSchema(@PathVariable UUID categoryId,
            @RequestBody Map<String, Object> body) {
        String attrKey = (String) body.get("attrKey");
        String label = (String) body.get("label");
        boolean required = Boolean.TRUE.equals(body.get("required"));
        int position = body.get("position") instanceof Number n ? n.intValue() : 0;
        return ResponseEntity.ok(catalogUseCase.upsertCategoryAttributeSchema(categoryId, attrKey, label, required,
                position));
    }

    @DeleteMapping("/category-attribute-schema/{id}")
    public ResponseEntity<Void> deleteCategoryAttributeSchema(@PathVariable UUID id) {
        catalogUseCase.deleteCategoryAttributeSchema(id);
        return ResponseEntity.noContent().build();
    }
}
