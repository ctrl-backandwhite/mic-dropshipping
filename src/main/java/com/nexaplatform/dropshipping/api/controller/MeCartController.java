package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.CartItemDto;
import com.nexaplatform.dropshipping.application.service.CartService;
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
 * Carrito SINCRONIZADO del usuario autenticado: la cesta deja de vivir en el navegador y pasa a seguir a
 * la persona entre web y app.
 *
 * <p>El id de usuario es SIEMPRE el subject de la sesión ({@code auth.getName()}); no se acepta ni por
 * parámetro ni en el cuerpo. Esa es la única defensa que importa aquí: si la cesta se pudiera direccionar
 * por id, cualquiera leería o vaciaría la de otro (IDOR). La autenticación la exige la cadena de
 * seguridad por defecto (todo {@code /api/me/**} pide usuario autenticado), como en favoritos y guardados.
 *
 * <p>Todas las operaciones devuelven la CESTA COMPLETA ya actualizada: el cliente sustituye su estado con
 * la respuesta en lugar de reconstruirlo, así dos dispositivos no pueden acabar con vistas distintas.
 */
@Tag(name = "Cart")
@RestController
@RequestMapping("/api/me/cart")
@RequiredArgsConstructor
public class MeCartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<List<CartItemDto>> list(Authentication auth) {
        return ResponseEntity.ok(cartService.list(userId(auth)));
    }

    /** Añade o actualiza una línea FIJANDO su cantidad; devuelve la cesta completa actualizada. */
    @PutMapping
    public ResponseEntity<List<CartItemDto>> save(Authentication auth, @Valid @RequestBody CartItemDto item) {
        return ResponseEntity.ok(cartService.upsert(userId(auth), item));
    }

    /** Sube y funde una lista completa SUMANDO cantidades (lo que un invitado llenó en local, al entrar). */
    @PostMapping("/merge")
    public ResponseEntity<List<CartItemDto>> merge(Authentication auth, @RequestBody List<CartItemDto> items) {
        return ResponseEntity.ok(cartService.merge(userId(auth), items));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<List<CartItemDto>> remove(Authentication auth, @PathVariable UUID productId,
            @RequestParam(required = false) UUID variantId) {
        return ResponseEntity.ok(cartService.remove(userId(auth), productId, variantId));
    }

    /** Vacía la cesta entera — al completar un pedido. Devuelve la lista vacía por uniformidad. */
    @DeleteMapping
    public ResponseEntity<List<CartItemDto>> clear(Authentication auth) {
        return ResponseEntity.ok(cartService.clear(userId(auth)));
    }

    private static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }
}
