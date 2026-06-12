package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.ShopConnectDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ShopDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopInboundSecretDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopListingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShopPlatformDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the user's shop integrations resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "My Shops")
public interface ShopApi {

    @Operation(summary = "List the authenticated user's connected shops")
    @GetMapping
    ResponseEntity<List<ShopDtoOut>> list(Authentication auth);

    @Operation(summary = "Connect a new shop integration")
    @PostMapping
    ResponseEntity<ShopDtoOut> connect(Authentication auth, @Valid @RequestBody ShopConnectDtoIn req);

    @Operation(summary = "Trigger a sync for a connected shop")
    @PostMapping("/{id}/sync")
    ResponseEntity<ShopDtoOut> sync(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Disconnect a shop integration")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> disconnect(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "Rotate the inbound HMAC secret used to sign shop order webhooks")
    @PostMapping("/{id}/inbound-secret/rotate")
    ResponseEntity<ShopInboundSecretDtoOut> rotateInboundSecret(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "List a catalog product into a connected shop")
    @PostMapping("/{id}/listings/{productId}")
    ResponseEntity<ShopListingDtoOut> listProduct(Authentication auth, @PathVariable UUID id,
            @PathVariable UUID productId);

    @Operation(summary = "List the product listings of a connected shop")
    @GetMapping("/{id}/listings")
    ResponseEntity<List<ShopListingDtoOut>> listings(Authentication auth, @PathVariable UUID id);

    @Operation(summary = "List the supported shop platforms")
    @GetMapping("/platforms")
    ResponseEntity<List<ShopPlatformDtoOut>> platforms();
}
