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
 * Una línea del CARRITO ACTIVO de un usuario, ya en el servidor. Antes el carrito vivía solo en el
 * navegador ({@code localStorage}), así que era del dispositivo: lo que alguien añadía en la web no
 * aparecía en la app. Al colgarlo del usuario, la cesta le sigue entre navegador, móvil y app.
 *
 * <p>Guarda las referencias (product/variant) y un snapshot de lo que se ve (título, imagen, variante,
 * precio) para poder pintar la línea aunque la variante haya cambiado tras un re-import — por eso
 * {@code variantId} no lleva FK: al re-importar un producto sus variantes se recrean y el id cambia.
 *
 * <p>El precio guardado es SOLO de pintado: el importe que se cobra lo recalcula el presupuesto
 * ({@code /api/catalog/cart-quote}) con la tarifa y la tasa del día, para que nunca se cobre un precio
 * congelado hace semanas.
 *
 * <p>Hermana de {@link SavedCartItemEntity} ("guardar para más tarde"), con la MISMA forma de línea para
 * poder mover una línea de una lista a la otra sin transformaciones.
 */
@Entity
@Table(name = "cart_item", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id", "variant_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItemEntity extends BaseEntity {

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
