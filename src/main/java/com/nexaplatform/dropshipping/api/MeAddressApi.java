package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the authenticated user's Addresses resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "My Addresses")
public interface MeAddressApi {

    @Operation(summary = "List the authenticated user's addresses")
    @GetMapping
    ResponseEntity<List<AddressDtoOut>> list(Authentication auth);

    @Operation(summary = "Create a new address for the authenticated user")
    @PostMapping
    ResponseEntity<AddressDtoOut> create(Authentication auth, @Valid @RequestBody AddressDtoIn req);

    @Operation(summary = "Update an existing address of the authenticated user")
    @PutMapping("/{id}")
    ResponseEntity<AddressDtoOut> update(Authentication auth, @PathVariable UUID id,
                                         @Valid @RequestBody AddressDtoIn req);

    @Operation(summary = "Delete an address of the authenticated user")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(Authentication auth, @PathVariable UUID id);
}
