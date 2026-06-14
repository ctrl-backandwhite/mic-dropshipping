package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReindexResultDtoOut;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    private final CatalogUseCase catalogUseCase;

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
    public PageResponse<ProductSummaryView> list(String status, UUID categoryId, int page, int size, String lang) {
        return PageResponse.from(catalogUseCase.listProductsForAdmin(status, categoryId, page, size, lang));
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

    @Override
    public ResponseEntity<Void> deleteVariant(UUID id) {
        catalogUseCase.deleteVariant(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<com.nexaplatform.dropshipping.api.dto.out.ImageUploadDtoOut> uploadImage(
            org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException("No se ha enviado ningún archivo");
        }
        try {
            String url = catalogUseCase.uploadImage(file.getBytes(), file.getContentType(), file.getOriginalFilename());
            return ResponseEntity.ok(new com.nexaplatform.dropshipping.api.dto.out.ImageUploadDtoOut(url));
        } catch (java.io.IOException e) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException("No se pudo leer el archivo subido");
        }
    }

    @Override
    public ResponseEntity<com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView> addProductImage(
            UUID productId, com.nexaplatform.dropshipping.api.dto.in.AddProductImageDtoIn req) {
        return new ResponseEntity<>(catalogUseCase.addProductImage(productId, req.getUrl(), req.getRole()),
                org.springframework.http.HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<Void> deleteProductImage(UUID imageId) {
        catalogUseCase.deleteProductImage(imageId);
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
    public ResponseEntity<BulkResultDtoOut> bulkProducts(List<BulkProductDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateProducts(rows));
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> bulkCategories(List<BulkCategoryDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateCategories(rows));
    }
}
