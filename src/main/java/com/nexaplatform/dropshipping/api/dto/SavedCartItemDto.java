package com.nexaplatform.dropshipping.api.dto;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SavedCartItemEntity;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una línea de "guardar para más tarde", tal como viaja hacia y desde el frontend (misma forma que la
 * línea del carrito local, para poder moverla entre carrito y guardado sin transformaciones).
 */
public record SavedCartItemDto(@NotNull UUID productId, UUID variantId, String sku, @NotBlank String slug,
        @NotBlank String title, String image, String variantLabel, @NotNull BigDecimal unitPriceSource,
        @NotBlank String sourceCurrency, @Min(1) @Max(100_000) int quantity, Integer moq, BigDecimal unitPriceDisplay,
        String displayCurrency, String displaySymbol) {

    public static SavedCartItemDto fromEntity(SavedCartItemEntity e) {
        return new SavedCartItemDto(e.getProductId(), e.getVariantId(), e.getSku(), e.getSlug(), e.getTitle(),
                e.getImageUrl(), e.getVariantLabel(), e.getUnitPriceSource(), e.getSourceCurrency(), e.getQuantity(),
                e.getMoq(), e.getUnitPriceDisplay(), e.getDisplayCurrency(), e.getDisplaySymbol());
    }
}
