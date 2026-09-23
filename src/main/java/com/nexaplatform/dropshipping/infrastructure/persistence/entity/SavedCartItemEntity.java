package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Una línea "guardada para más tarde" por un usuario. Guarda las referencias (product/variant) y un
 * snapshot de lo que se ve (título, imagen, variante, precio) para poder pintarla y devolverla al
 * carrito aunque la variante haya cambiado tras un re-import. El carrito activo NO se persiste aquí:
 * vive en el navegador; esto es solo la lista apartada, ligada al usuario.
 */
@Entity
@Table(name = "saved_cart_item", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id",
        "variant_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedCartItemEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;

    /** Variante elegida; null para el producto base. Sin FK: al re-importar cambia el id. */
    @Column(name = "variant_id", columnDefinition = "uuid")
    private UUID variantId;

    @Column(name = "sku", length = 120)
    private String sku;

    @Column(name = "slug", nullable = false, length = 300)
    private String slug;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "variant_label", length = 300)
    private String variantLabel;

    @Column(name = "unit_price_source", nullable = false, precision = 18, scale = 4)
    private BigDecimal unitPriceSource;

    @Column(name = "source_currency", nullable = false, length = 8)
    private String sourceCurrency;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "moq")
    private Integer moq;

    @Column(name = "unit_price_display", precision = 18, scale = 4)
    private BigDecimal unitPriceDisplay;

    @Column(name = "display_currency", length = 8)
    private String displayCurrency;

    @Column(name = "display_symbol", length = 8)
    private String displaySymbol;
}
