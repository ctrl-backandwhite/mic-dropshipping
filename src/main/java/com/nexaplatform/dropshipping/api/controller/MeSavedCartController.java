package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.SavedCartItemDto;
import com.nexaplatform.dropshipping.application.service.SavedCartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * "Guardar para más tarde" del usuario autenticado. El id de usuario es el subject de la sesión
 * ({@code auth.getName()}); la protección la aporta la cadena de seguridad por defecto (todo
 * {@code /api/me/**} exige usuario autenticado), como en favoritos.
 */
@Tag(name = "Saved cart")
@RestController
@RequestMapping("/api/me/saved-cart")
@RequiredArgsConstructor
public class MeSavedCartController {

    private final SavedCartService savedCartService;

    @GetMapping
    public ResponseEntity<List<SavedCartItemDto>> list(Authentication auth) {
        return ResponseEntity.ok(savedCartService.list(userId(auth)));
    }

    /** Guarda (o suma sobre) una línea; devuelve la lista completa actualizada. */
    @PutMapping
    public ResponseEntity<List<SavedCartItemDto>> save(Authentication auth, @Valid @RequestBody SavedCartItemDto item) {
        return ResponseEntity.ok(savedCartService.upsert(userId(auth), item));
    }

    /** Sube y fusiona una lista completa (lo que un invitado guardó en local, al iniciar sesión). */
    @PostMapping("/merge")
    public ResponseEntity<List<SavedCartItemDto>> merge(Authentication auth,
            @RequestBody List<SavedCartItemDto> items) {
        return ResponseEntity.ok(savedCartService.merge(userId(auth), items));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<List<SavedCartItemDto>> remove(Authentication auth, @PathVariable UUID productId,
            @RequestParam(required = false) UUID variantId) {
        return ResponseEntity.ok(savedCartService.remove(userId(auth), productId, variantId));
    }

    private static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
