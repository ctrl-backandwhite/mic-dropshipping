package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.UUID;

/** DROP-499: input payload for the admin quick-edit of a product. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProductQuickEditDtoIn {

    @Schema(description = "Localized product title")
    private String title;

    @Schema(description = "Brand")
    private String brand;

    @Schema(description = "Base price in the product currency")
    private BigDecimal basePrice;

    @Schema(description = "ISO currency code")
    private String currency;

    @Schema(description = "Minimum order quantity")
    private Integer moq;

    @Schema(description = "Localized short description")
    private String shortDescription;

    @Schema(description = "Localized long description")
    private String description;

    @Schema(description = "DROP-673: URL del vídeo principal del producto (real). Cadena vacía lo elimina.")
    private String videoUrl;

    @Schema(description = "DROP-688: meta título SEO del idioma activo (edición manual).")
    private String metaTitle;

    @Schema(description = "DROP-688: meta descripción SEO del idioma activo (edición manual).")
    private String metaDescription;

    @Schema(description = "Verificación manual del admin (true = revisado OK, false = pendiente/con error).")
    private Boolean verified;

    @Schema(description = "Categoría del producto (id). Permite reasignar la categoría desde el admin.")
    private UUID categoryId;
}
