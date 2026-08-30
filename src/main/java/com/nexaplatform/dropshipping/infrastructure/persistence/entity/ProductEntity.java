package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "product", uniqueConstraints = @UniqueConstraint(columnNames = {"source", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 220)
    private String slug;

    @Column(name = "external_id", nullable = false, length = 120)
    private String externalId;

    @Column(nullable = false, length = 40)
    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private SupplierEntity supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CategoryEntity category;

    @Column(name = "title_zh", nullable = false, length = 500)
    private String titleZh;

    @Column(name = "short_description_zh", length = 2000)
    private String shortDescriptionZh;

    @Column(name = "description_zh", columnDefinition = "TEXT")
    private String descriptionZh;

    @Column(length = 200)
    private String brand;

    /**
     * Identidad del fabricante exigida por el art. 19.a del Reglamento (UE) 2023/988 en la venta a
     * distancia: la oferta debe indicar su nombre, dirección postal y correo electrónico. {@link #brand} no
     * vale para esto —es una marca comercial, no una identidad contactable— y además llega vacío de 1688 en
     * la práctica totalidad del catálogo.
     */
    @Column(name = "manufacturer_name", length = 200)
    private String manufacturerName;

    @Column(name = "manufacturer_address", length = 300)
    private String manufacturerAddress;

    @Column(name = "manufacturer_email", length = 200)
    private String manufacturerEmail;

    @Column(nullable = false)
    private int moq;

    @Column(name = "base_price", precision = 12, scale = 4)
    private BigDecimal basePrice;

    /** Flete de envío en CNY (misma moneda que base_price). Se suma al total SIN margen. */
    @Column(name = "shipping_cny", precision = 12, scale = 4)
    private BigDecimal shippingCny;

    /** IVA en CNY (valor fijo de carga, misma moneda que base_price). Se suma al total SIN margen. */
    @Column(name = "iva_cny", precision = 12, scale = 4)
    private BigDecimal ivaCny;

    /**
     * Recargo fijo por producto en CNY (misma moneda que base_price). Default 0. Lo edita el admin
     * por producto, por categoría o masivamente para todo el catálogo, y se suma como componente del
     * precio final (igual que IVA y envío). Es un cargo directo del precio de venta, no un coste de
     * proveedor.
     */
    @Column(name = "surcharge_cny", precision = 12, scale = 4)
    private BigDecimal surchargeCny;

    @Column(length = 8)
    private String currency;

    @Column(name = "weight_grams")
    private Integer weightGrams;

    @Column(name = "length_mm")
    private Integer lengthMm;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "hs_code", length = 40)
    private String hsCode;

    /** InvoicePart (材质) que se declara a YunExpress, en inglés. Sembrado desde el perfil de la categoría. */
    @Column(name = "customs_material", length = 255)
    private String customsMaterial;

    /** InvoiceUsage (用途) que se declara a YunExpress, en inglés. Sembrado desde el perfil de la categoría. */
    @Column(name = "customs_usage", length = 255)
    private String customsUsage;

    /**
     * Presencia de batería: determina el {@code PackageType} de YunExpress (0 = 普货 carga general,
     * 1 = 带电 con batería) y, por tanto, el canal y la tarifa. Valores: NONE, BUILT_IN, WITH_EQUIPMENT.
     */
    @Column(name = "battery_type", length = 20, nullable = false)
    @Builder.Default
    private String batteryType = "NONE";

    @Column(name = "package_weight_grams")
    private Integer packageWeightGrams;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "warranty_months")
    private Integer warrantyMonths;

    @Column(name = "country_of_origin", length = 2)
    private String countryOfOrigin;

    @Column(name = "return_policy_days")
    private Integer returnPolicyDays;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> certifications;

    @Column(name = "ship_from", length = 120)
    private String shipFrom;

    @Column(name = "free_shipping")
    private Boolean freeShipping;

    @Column(name = "self_pickup")
    private Boolean selfPickup;

    @Column(name = "has_video")
    private Boolean hasVideo;

    @Column(name = "video_url", length = 800)
    private String videoUrl;

    // ── v44: campos internacionales/1688 que faltaban ──
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "video_urls", columnDefinition = "jsonb")
    private List<String> videoUrls;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "sales_regions", columnDefinition = "jsonb")
    private List<String> salesRegions;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "rating_breakdown", columnDefinition = "jsonb")
    private Map<String, Integer> ratingBreakdown;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "cross_border_support", columnDefinition = "jsonb")
    private Map<String, Object> crossBorderSupport;

    @Column(name = "dropship_shipped_30d")
    private Integer dropshipShipped30d;

    @Column(name = "dropship_pickup_rate_48h", precision = 5, scale = 2)
    private BigDecimal dropshipPickupRate48h;

    @Column(name = "inventory_count")
    private Integer inventoryCount;

    @Column(name = "pod_enabled")
    private Boolean podEnabled;

    @Column(name = "brand_selected")
    private Boolean brandSelected;

    @Column(name = "ready_to_ship")
    private Boolean readyToShip;

    // Verificación manual del admin: false = pendiente/con error (reimportar), true = revisado y correcto.
    @Builder.Default
    @Column(name = "verified", nullable = false)
    private Boolean verified = false;

    @Column(name = "ar_model_url", length = 800)
    private String arModelUrl;

    @Column(name = "reviews_summary", columnDefinition = "TEXT")
    private String reviewsSummary;

    @Column(name = "reviews_sentiment", precision = 3, scale = 2)
    private BigDecimal reviewsSentiment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "review_count")
    private int reviewCount;

    @Column(name = "monthly_sales")
    private int monthlySales;

    @Column(name = "repurchase_rate", precision = 5, scale = 2)
    private BigDecimal repurchaseRate;

    @Column(name = "trend_score", precision = 8, scale = 4)
    private BigDecimal trendScore;

    @Column(name = "source_url", length = 800)
    private String sourceUrl;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ProductImageEntity> images = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductVariantEntity> variants = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductTranslationEntity> translations = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<VariantOptionEntity> variantOptions = new ArrayList<>();
}
