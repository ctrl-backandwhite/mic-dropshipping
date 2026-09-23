package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "product_variant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariantEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(length = 120)
    private String sku;

    /** v44: SKU del proveedor (1688) para reaprovisionar. */
    @Column(name = "supplier_sku_id", length = 120)
    private String supplierSkuId;

    @Column(length = 400)
    private String title;

    @Column(precision = 12, scale = 4)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    // DROP-675: peso del paquete y dimensiones reales por variante (para el cálculo de envío).
    @Column(name = "package_weight_grams")
    private Integer packageWeightGrams;

    /**
     * Envío nacional chino de ESTA variante, en CNY.
     *
     * <p>Lo calcula el scraper por tramos de peso (6 / 10 / 16 CNY). Va por variante porque una
     * misma ficha puede tener una talla de 800 g y otra de 1,2 kg, y el flete no es el mismo.
     *
     * <p>Nullable: un null significa "no declara envío propio" y entonces manda el del producto.
     * No es lo mismo que un cero, que sí es un importe.
     */
    @Column(name = "shipping_cny", precision = 12, scale = 4)
    private BigDecimal shippingCny;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(length = 80)
    private String barcode;

    @Column(name = "image_source_url", length = 800)
    private String imageSourceUrl;

    @Column(name = "image_cdn_url", length = 800)
    private String imageCdnUrl;

    /** Marca de fallo de espejado (origen muerto/404): evita reintentar la imagen en bucle. */
    @Column(name = "image_mirror_failed_at")
    private Instant imageMirrorFailedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", columnDefinition = "jsonb")
    private Map<String, String> options;

    @Column(nullable = false)
    private boolean active;
}
