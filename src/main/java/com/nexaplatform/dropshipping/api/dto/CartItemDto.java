package com.nexaplatform.dropshipping.api.dto;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CartItemEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una línea del carrito activo, tal como viaja hacia y desde el cliente (web o app). Misma forma que
 * {@link SavedCartItemDto} a propósito: así una línea se mueve entre el carrito y "guardar para más
 * tarde" —y entre el carrito local del invitado y el del servidor— sin transformaciones.
 *
 * <p>Fíjate en lo que NO lleva: ningún identificador de usuario. El dueño de la línea sale siempre de la
 * autenticación; aceptarlo en el cuerpo abriría un IDOR (escribir en la cesta de otro).
 */
public record CartItemDto(
        @NotNull UUID productId,
        UUID variantId,
        String sku,
        @NotBlank String slug,
        @NotBlank String title,
        String image,
        String variantLabel,
        @NotNull BigDecimal unitPriceSource,
        @NotBlank String sourceCurrency,
        @Min(1) @Max(100_000) int quantity,
        Integer moq,
        BigDecimal unitPriceDisplay,
        String displayCurrency,
        String displaySymbol) {

    public static CartItemDto fromEntity(CartItemEntity e) {
        return new CartItemDto(e.getProductId(), e.getVariantId(), e.getSku(), e.getSlug(), e.getTitle(),
                e.getImageUrl(), e.getVariantLabel(), e.getUnitPriceSource(), e.getSourceCurrency(),
                e.getQuantity(), e.getMoq(), e.getUnitPriceDisplay(), e.getDisplayCurrency(),
                e.getDisplaySymbol());
    }

    /**
     * La misma línea con otra imagen.
     *
     * <p>La usa el carrito para rellenar al SERVIR la foto de las líneas que se guardaron sin ella, sin
     * tocar lo almacenado. Se devuelve una copia y no se muta el registro porque un {@code record} es
     * inmutable a propósito: la línea que viaja al cliente no puede depender de en qué orden alguien la
     * modificó por el camino.
     */
    public CartItemDto conImagen(String imagen) {
        return new CartItemDto(productId, variantId, sku, slug, title, imagen, variantLabel,
                unitPriceSource, sourceCurrency, quantity, moq, unitPriceDisplay, displayCurrency,
                displaySymbol);
    }
}
