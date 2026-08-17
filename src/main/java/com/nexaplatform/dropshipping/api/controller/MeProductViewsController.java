package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * Historial de fichas visitadas por el usuario autenticado. La ficha llama a {@code POST /{productId}} al
 * abrirse y la página «lo que has visto» de su área lee {@code GET /}.
 *
 * <p>El usuario sale SIEMPRE de la autenticación y nunca de la petición: es lo que impide que nadie pueda
 * leer —ni ensuciar— el historial de otro.
 */
@Tag(name = "Product history")
@RestController
@RequestMapping("/api/me/product-views")
@RequiredArgsConstructor
public class MeProductViewsController {

    private final ProductViewHistoryService historyService;
    private final CatalogStorefrontReadService catalogReadService;

    @Operation(summary = "List the products the authenticated user has visited, most recent first")
    @GetMapping
    public ResponseEntity<PageResponse<ProductSummaryView>> list(Authentication auth,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "24") int size,
            @RequestParam(defaultValue = "es") String lang) {
        UUID userId = UUID.fromString(auth.getName());
        List<UUID> ids = historyService.viewedProductIds(userId);
        // Se reutiliza el listado por IDs de los favoritos: es exactamente lo que hace falta —respeta el
        // orden recibido, descarta los que ya no están activos y aplica el mismo pipeline de precios—, y
        // duplicarlo solo conseguiría que el historial y los favoritos pintaran precios distintos.
        return ResponseEntity.ok(catalogReadService.favorites(ids, page, size, lang));
    }

    @Operation(summary = "Record that the authenticated user opened a product page (idempotent)")
    @PostMapping("/{productId}")
    public ResponseEntity<Map<String, Object>> record(Authentication auth, @PathVariable UUID productId) {
        UUID userId = UUID.fromString(auth.getName());
        historyService.record(userId, productId);
        return ResponseEntity.ok(Map.of("recorded", true));
    }
}
