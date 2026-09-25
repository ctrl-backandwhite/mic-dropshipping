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

    @Schema(description = "Shipping cost in CNY, as declared by the supplier listing")
    private BigDecimal shippingCny;

    @Schema(description = "Import VAT in CNY")
    /** Margen interno en PORCENTAJE sobre el coste (antes viajaba como importe, «ivaCny»). */
    private BigDecimal margenInternoPct;

    @Schema(description = "Recargo fijo por producto en CNY (default 0). Se suma al precio final.")
    private BigDecimal surchargeCny;

    @Schema(description = "Bolsa de subvención del porte en CNY (default 0). Se descuenta del envío del pedido.")
    private BigDecimal shippingUserCny;

    @Schema(description = "Bolsa de subvención del arancel en CNY (default 0). Se descuenta del arancel.")
    private BigDecimal dutyUserCny;

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

    // Identidad del fabricante exigida por el art. 19.a del Reglamento (UE) 2023/988 en la venta a
    // distancia. `brand` no cubre esto: es una marca comercial, no una identidad con la que contactar.
    @Schema(description = "Nombre o razón social del fabricante (Reg. (UE) 2023/988 art. 19.a)")
    private String manufacturerName;

    @Schema(description = "Dirección postal del fabricante (Reg. (UE) 2023/988 art. 19.a)")
    private String manufacturerAddress;

    @Schema(description = "Correo electrónico del fabricante (Reg. (UE) 2023/988 art. 19.a)")
    private String manufacturerEmail;
}
