package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Categories resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Categories")
public interface AdminCategoryApi {

    @Operation(summary = "List all admin categories")
    @GetMapping
    ResponseEntity<List<AdminCategoryDtoOut>> list();

    @Operation(summary = "Toggle a category active state by id")
    @PutMapping("/{id}/toggle")
    ResponseEntity<AdminCategoryDtoOut> toggle(@PathVariable UUID id);

    @Operation(summary = "Update a category by id (slug, names, parent, position, icon)")
    @PutMapping("/{id}")
    ResponseEntity<AdminCategoryDtoOut> update(@PathVariable UUID id, @Valid @RequestBody AdminCategoryUpsertDtoIn req);

    @Operation(summary = "Delete a category by id (fails if products are associated)")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
