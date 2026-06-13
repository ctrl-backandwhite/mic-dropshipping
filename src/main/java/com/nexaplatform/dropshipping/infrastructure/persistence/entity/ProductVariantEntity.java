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

    @Column(length = 80)
    private String barcode;

    @Column(name = "image_source_url", length = 800)
    private String imageSourceUrl;

    @Column(name = "image_cdn_url", length = 800)
    private String imageCdnUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json", columnDefinition = "jsonb")
    private Map<String, String> options;

    @Column(nullable = false)
    private boolean active;
}
