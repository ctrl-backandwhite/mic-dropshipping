package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the Admin Pricing rules resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Admin Pricing")
public interface AdminPricingApi {

    @Operation(summary = "List all price rules")
    @ApiResponse(responseCode = "200", description = "Rules listed")
    @GetMapping
    ResponseEntity<List<PriceRuleDtoOut>> list();

    @Operation(summary = "Create a price rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @PostMapping
    ResponseEntity<PriceRuleDtoOut> create(@Valid @RequestBody PriceRuleDtoIn req);

    @Operation(summary = "Update a price rule by id")
    @ApiResponse(responseCode = "200", description = "Rule updated")
    @PutMapping("/{id}")
    ResponseEntity<PriceRuleDtoOut> update(@PathVariable UUID id, @Valid @RequestBody PriceRuleDtoIn req);

    @Operation(summary = "Delete a price rule by id")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
