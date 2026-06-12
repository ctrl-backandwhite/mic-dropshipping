package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

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

    @Operation(summary = "List products with paging and optional status filter")
    @GetMapping("/products")
    PageResponse<ProductSummaryView> list(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Get product detail by id")
    @GetMapping("/products/{id}")
    ProductDetailView detail(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Update a product status")
    @PutMapping("/products/{id}/status")
    ResponseEntity<Void> updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateProductStatusRequest req);

    @Operation(summary = "Quick-edit a product")
    @PutMapping("/products/{id}")
    ProductDetailView quickEdit(@PathVariable UUID id,
            @Valid @RequestBody com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn req,
            @RequestParam(defaultValue = "es") String lang);

    @Operation(summary = "Duplicate a product")
    @PostMapping("/products/{id}/duplicate")
    ProductDetailView duplicate(@PathVariable UUID id, @RequestParam(defaultValue = "es") String lang);
}
