package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.ProductFavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Favoritos (wishlist) del usuario autenticado. El corazón del catálogo se marca con {@code /ids} (para no
 * tocar el pipeline de precios); la página de favoritos usa {@code GET /} con los datos de producto y precios.
 */
@Tag(name = "Favorites")
@RestController
@RequestMapping("/api/me/favorites")
@RequiredArgsConstructor
public class MeFavoritesController {

    private final ProductFavoriteService favoriteService;
    private final CatalogStorefrontReadService catalogReadService;

    @Operation(summary = "List the authenticated user's favorite products (with prices)")
    @GetMapping
    public ResponseEntity<PageResponse<ProductSummaryView>> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "es") String lang) {
        UUID userId = UUID.fromString(auth.getName());
        List<UUID> ids = favoriteService.favoriteProductIds(userId);
        return ResponseEntity.ok(catalogReadService.favorites(ids, page, size, lang));
    }

    @Operation(summary = "IDs of the authenticated user's favorite products (to mark hearts in the catalog)")
    @GetMapping("/ids")
    public ResponseEntity<List<UUID>> ids(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(favoriteService.favoriteProductIds(userId));
    }

    @Operation(summary = "Add a product to favorites (idempotent)")
    @PostMapping("/{productId}")
    public ResponseEntity<Map<String, Object>> add(Authentication auth, @PathVariable UUID productId) {
        UUID userId = UUID.fromString(auth.getName());
        favoriteService.add(userId, productId);
        return ResponseEntity.ok(Map.of("favorite", true));
    }

    @Operation(summary = "Remove a product from favorites (idempotent)")
    @DeleteMapping("/{productId}")
    public ResponseEntity<Map<String, Object>> remove(Authentication auth, @PathVariable UUID productId) {
        UUID userId = UUID.fromString(auth.getName());
        favoriteService.remove(userId, productId);
        return ResponseEntity.ok(Map.of("favorite", false));
    }
}
