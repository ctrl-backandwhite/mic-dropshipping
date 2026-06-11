package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public PageResponse<ProductSummaryView> list(String status, int page, int size, String lang) {
        return PageResponse.from(catalogUseCase.listProductsForAdmin(status, page, size, lang));
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
}
