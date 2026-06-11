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
import com.nexaplatform.dropshipping.application.service.CatalogService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController implements AdminCatalogApi {

    private final CatalogService catalogService;
    private final CategoryRepository categoryRepository;

    @Override
    public ResponseEntity<UUID> upsertSupplier(IngestSupplierRequest req) {
        return ResponseEntity.ok(catalogService.upsertSupplier(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertCategory(IngestCategoryRequest req) {
        if (categoryRepository.findBySlug(req.slug()).isPresent()) {
            throw new com.nexaplatform.dropshipping.api.exception.BusinessException(
                    "Ya existe una categoría con slug \"" + req.slug() + "\"");
        }
        return ResponseEntity.ok(catalogService.upsertCategory(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertProduct(IngestProductRequest req) {
        return ResponseEntity.ok(catalogService.upsertProduct(req).getId());
    }

    @Override
    public PageResponse<ProductSummaryView> list(
            String status,
            int page,
            int size,
            String lang) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        // DROP-453: tolerar 'ALL', '', 'undefined' (axios a veces serializa undefined
        // como string)
        // y valores no válidos — devuelve listado completo en esos casos.
        ProductStatus s = null;
        if (status != null && !status.isBlank()
                && !"ALL".equalsIgnoreCase(status)
                && !"undefined".equalsIgnoreCase(status)
                && !"null".equalsIgnoreCase(status)) {
            try {
                s = ProductStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                s = null;
            }
        }
        return PageResponse.from(catalogService.listProducts(s, pageable, lang));
    }

    @Override
    public ProductDetailView detail(UUID id, String lang) {
        return catalogService.getProductById(id, lang);
    }

    @Override
    public ResponseEntity<Void> updateStatus(UUID id, UpdateProductStatusRequest req) {
        catalogService.updateStatus(id, ProductStatus.valueOf(req.status().toUpperCase()));
        return ResponseEntity.noContent().build();
    }

    // DROP-499: edición rápida + duplicado.
    @Override
    public ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang) {
        return catalogService.quickEdit(id, req, lang);
    }

    @Override
    public ProductDetailView duplicate(UUID id, String lang) {
        return catalogService.duplicateProduct(id, lang);
    }
}
