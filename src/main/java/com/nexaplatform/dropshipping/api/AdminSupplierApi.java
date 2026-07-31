package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminSupplierUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
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

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Suppliers resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Suppliers")
public interface AdminSupplierApi {

    @Operation(summary = "List suppliers for the admin panel (paginated, most-recent-first)")
    @GetMapping
    ResponseEntity<PageResponse<AdminSupplierDtoOut>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) String country, @RequestParam(required = false) Boolean verified,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size);

    @Operation(summary = "Toggle the verified flag of a supplier")
    @PostMapping("/{id}/verify")
    ResponseEntity<AdminSupplierToggleDtoOut> toggleVerified(@PathVariable UUID id);

    @Operation(summary = "Toggle the trustPass flag of a supplier")
    @PostMapping("/{id}/trustpass")
    ResponseEntity<AdminSupplierToggleDtoOut> toggleTrustPass(@PathVariable UUID id);

    @Operation(summary = "Create a supplier manually")
    @PostMapping("/create")
    ResponseEntity<AdminSupplierDtoOut> create(@Valid @RequestBody AdminSupplierUpsertDtoIn req);

    @Operation(summary = "Update a supplier")
    @PutMapping("/{id}")
    ResponseEntity<AdminSupplierDtoOut> update(@PathVariable UUID id, @Valid @RequestBody AdminSupplierUpsertDtoIn req);

    @Operation(summary = "Delete a supplier (refused if it has products)")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
