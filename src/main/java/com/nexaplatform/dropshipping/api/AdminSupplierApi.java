package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Suppliers resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Suppliers")
public interface AdminSupplierApi {

    @Operation(summary = "List suppliers for the admin panel")
    @GetMapping
    ResponseEntity<List<AdminSupplierDtoOut>> list();

    @Operation(summary = "Toggle the verified flag of a supplier")
    @PostMapping("/{id}/verify")
    ResponseEntity<AdminSupplierToggleDtoOut> toggleVerified(@PathVariable UUID id);

    @Operation(summary = "Toggle the trustPass flag of a supplier")
    @PostMapping("/{id}/trustpass")
    ResponseEntity<AdminSupplierToggleDtoOut> toggleTrustPass(@PathVariable UUID id);
}
