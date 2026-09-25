package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.BusAnuncioEstado;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
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
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Margen interno en PORCENTAJE sobre el coste del proveedor (25-sep-2026).
     *
     * <p>Sustituye a la antigua columna {@code iva_cny}, que nunca fue el IVA de China —ese es el
     * 13 %— sino exactamente el 50 % de la base en los 263 productos del catálogo: un margen interno
     * con el nombre cambiado y guardado como importe absoluto, que había que recalcular a mano cada
     * vez que se movía el coste del proveedor. En porcentaje se ajusta solo.
     *
     * <p>Nulo significa «el porcentaje por defecto», no «cero». La columna es nulable a propósito: un
     * NOT NULL con DEFAULT no protege del nulo explícito que manda la carga masiva.
     */
    @Column(name = "margen_interno_pct", precision = 6, scale = 3)
    private BigDecimal margenInternoPct;

    /**
     * Recargo del producto en PORCENTAJE sobre el coste del proveedor (25-sep-2026).
     *
     * <p>Lo edita el admin por producto, por categoría o masivamente para todo el catálogo, y se suma
     * como componente del precio final SIN margen: es un cargo directo del precio de venta, no un coste
     * de proveedor.
     *
     * <p>Sustituye al antiguo {@code surcharge_cny}, que era un importe fijo y por eso no seguía al
     * coste: si el proveedor subía el precio, el recargo se quedaba donde estaba y había que rehacerlo
     * a mano producto a producto, o se desfasaba en silencio. Mismo arreglo que se le hizo al margen
     * interno en la v174. La columna vieja se queda en la base —es NOT NULL con DEFAULT 0, así que al
     * no mapearla el INSERT usa su valor por omisión— pero ya no la lee nadie.
     *
     * <p>Nulable, y nulo aporta CERO. No se inventa un porcentaje por defecto: eso encarecería en
     * silencio cualquier producto al que le falte el dato.
     */
    @Column(name = "surcharge_pct", precision = 9, scale = 3)
    private BigDecimal surchargePct;

    /**
     * La categoría que 1688 declara para este producto, tal como viene.
     *
     * <p>NO decide dónde se archiva: de eso se encarga la categoría propia, que elige el pipeline de
     * carga. Esto se guarda para poder AUDITAR esa decisión después, que es lo que no se podía hacer:
     * el 11-sep-2026 había 656 productos mal archivados y hubo que deducirlo del título, el mismo
     * dato con el que se había equivocado el clasificador. Con la categoría de origen delante se
     * compara contra lo que dijo el proveedor.
     *
     * <p>También es lo que permite poblar {@code category_1688_mapping} sin adivinar: hoy ese mapeo se
     * aprende por moda estadística del propio catálogo y hereda sus errores —19 de 42 contradecían el
     * género declarado en el nombre chino—.
     */
    @Column(name = "category_1688_id", length = 60)
    private String category1688Id;

    @Column(name = "category_1688_name", length = 200)
    private String category1688Name;

    /**
     * Bolsa de subvención del PORTE, en CNY (misma moneda que base_price). Default 0. La asigna el
     * admin por producto, por categoría o en lote, y en el checkout se suma UNA VEZ POR PRODUCTO —no
     * por unidad— para descontarla del porte cotizado por el transportista. Si la suma iguala o supera
     * el porte, el envío queda cubierto.
     *
     * <p>Sustituye al cálculo de la subvención a partir del margen: el envío ya no lo paga la ganancia
     * del pedido, lo paga el importe que el admin haya asignado aquí.
     */
    @Column(name = "shipping_user_cny", precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal shippingUserCny = BigDecimal.ZERO;

    /**
     * Bolsa de subvención del ARANCEL, en CNY. Espejo exacto de {@link #shippingUserCny}: se descuenta
     * del derecho de aduana calculado.
     *
     * <p>Abarata lo que el cliente PAGA; el derecho declarado y remitido sigue siendo el íntegro.
     * Bajar el declarado sería infradeclarar, y de eso responde el declarante.
     */
    @Column(name = "duty_user_cny", precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal dutyUserCny = BigDecimal.ZERO;

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

    // ── v164: el vídeo también se espeja a nuestro almacenamiento, como las imágenes ──
    // La vista prefiere videoCdnUrl sobre videoUrl, así que en cuanto el espejado termina el navegador
    // deja de ir a pedirle el vídeo a Alibaba.
    @Column(name = "video_cdn_url", length = 800)
    private String videoCdnUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_mirror_status", length = 20)
    private MirrorStatus videoMirrorStatus;

    @Column(name = "video_bytes")
    private Long videoBytes;

    @Column(name = "video_hash", length = 64)
    private String videoHash;

    @Column(name = "video_mirror_attempts")
    private Integer videoMirrorAttempts;

    @Column(name = "video_mirrored_at")
    private Instant videoMirroredAt;

    // ── v165: anuncio al bus, diferido ──
    // La petición solo deja la marca; el barrido construye la ficha y publica. Se marca dentro de la
    // MISMA transacción que el cambio: es lo que garantiza que un producto certificado no se quede sin
    // anunciar si el proceso se cae justo después de guardar.
    @Enumerated(EnumType.STRING)
    @Column(name = "bus_estado", length = 20)
    private BusAnuncioEstado busEstado;

    @Column(name = "bus_intentos")
    private Integer busIntentos;

    /** Motivo del último fallo. Se guarda para poder ENSEÑARLO: un producto que no llega, se ve. */
    @Column(name = "bus_error", columnDefinition = "text")
    private String busError;

    @Column(name = "bus_anunciado_at")
    private Instant busAnunciadoAt;

    /**
     * Deja constancia de que hay algo que contarle al bus sobre este producto.
     *
     * <p>Es lo único que hace la petición: un cambio de columna, sin consultas ni serialización. El
     * evento concreto —certificado o retirado— lo decide el barrido mirando cómo ha quedado el
     * producto, así que si se marca y desmarca varias veces seguidas se anuncia el estado final, que
     * es justo lo que tiene que llegar al destino.
     */
    public void marcarParaAnunciarAlBus() {
        this.busEstado = BusAnuncioEstado.PENDIENTE;
        this.busIntentos = 0;
        this.busError = null;
    }

    /**
     * Cambia la dirección de origen del vídeo y lo deja en cola para espejar si de verdad es otra.
     *
     * <p>Va aquí y no en cada sitio que edita el producto —la importación masiva y el panel— porque
     * olvidarlo en uno de los dos no rompe nada visible: el producto se guarda, la ficha enseña el vídeo
     * del proveedor y nadie se entera de que ese no se ha traído nunca. Lo espejado antes se descarta
     * porque pertenece a OTRO vídeo, y el contador de intentos vuelve a cero: la dirección nueva merece
     * sus oportunidades aunque la vieja las hubiera agotado.
     */
    public void cambiarVideoUrl(String nueva) {
        String anterior = this.videoUrl;
        this.videoUrl = nueva;
        if (Objects.equals(anterior, nueva)) {
            return;
        }
        this.videoCdnUrl = null;
        this.videoHash = null;
        this.videoBytes = null;
        this.videoMirroredAt = null;
        this.videoMirrorAttempts = 0;
        this.videoMirrorStatus = nueva != null && !nueva.isBlank() ? MirrorStatus.PENDING : null;
    }

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

    /*
     * Las tres colecciones se traen POR LOTES, no una consulta por producto.
     *
     * <p>Cualquier pantalla que pinte una lista —el historial de visitas, los favoritos, el listado del
     * escaparate— carga los productos de golpe y después pide a cada uno su título, su foto y sus
     * variantes. Con carga perezosa suelta, eso son dos o tres consultas MÁS por producto: una página de
     * cincuenta fichas se iba a más de ciento cincuenta viajes a la base para enseñar un dato que cabía
     * en tres. Con el lote, Hibernate junta los que tiene pendientes y los resuelve en una sola consulta
     * por colección.
     *
     * <p>El tamaño es mayor que la página más grande que se sirve (cien), así que ninguna lista se parte
     * en dos lotes.
     */
    @Builder.Default
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ProductImageEntity> images = new ArrayList<>();

    @Builder.Default
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductVariantEntity> variants = new ArrayList<>();

    @Builder.Default
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProductTranslationEntity> translations = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<VariantOptionEntity> variantOptions = new ArrayList<>();
}
