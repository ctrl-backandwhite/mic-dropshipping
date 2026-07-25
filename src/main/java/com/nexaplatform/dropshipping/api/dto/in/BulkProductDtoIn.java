package com.nexaplatform.dropshipping.api.dto.in;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One product row of a bulk JSON import. Friendly, flat shape (the heavy
 * {@code IngestProductRequest} is built server-side). The category is referenced
 * by slug; the supplier is optional (a default is picked when omitted).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkProductDtoIn {

    /**
     * Slug de la categoría interna. DROP-677: ya NO es obligatorio; si se omite, la categoría se
     * resuelve automáticamente desde category1688Id/category1688Name vía la tabla de mapeo.
     */
    private String categorySlug;

    /** DROP-677: id de la categoría de origen en 1688 (para resolución automática vía mapeo). */
    private String category1688Id;

    /** DROP-677: nombre de la categoría de origen en 1688 (resolución por nombre si no hay id mapeado). */
    private String category1688Name;

    // Ya NO es @NotBlank: el contenido puede venir por el mapa `translations` (cualquier idioma). El
    // caso de uso valida que exista al menos un título y devuelve un mensaje claro si falta.
    private String titleEs;

    private String titleEn;

    private String titlePt;

    private String titleZh;

    private String descriptionEs;

    private String descriptionEn;

    private String descriptionPt;

    private String descriptionZh;

    private BigDecimal price;

    /**
     * OBLIGATORIO. Flete de envío en CNY (misma moneda que {@code price}). Se suma al total SIN margen.
     * Regla de carga: {@code envío = max(10, flete_1688)}.
     */
    private BigDecimal shippingCny;

    /**
     * OBLIGATORIO. Importe de IVA en CNY (misma moneda que {@code price}). Valor fijo que se ingresa en la
     * carga (la plataforma NO lo recalcula). Se suma al total SIN margen.
     */
    private BigDecimal ivaCny;

    private Integer moq;

    private Integer monthlySales;

    private BigDecimal rating;

    /** Optional supplier external id (1688); first available supplier used when null. */
    private String supplierExternalId;

    /** Nombre del proveedor/fábrica; si no existe se crea y se vincula (en vez de reutilizar el primero). */
    private String supplierName;

    /** Optional product manufacturer (stored in the product's brand field). */
    private String manufacturer;

    /**
     * URLs de imagen del producto. Se aceptan claves alternativas comunes ({@code images},
     * {@code photos}, {@code pictures}, {@code mainImage}) para que un JSON razonable no se rechace
     * por el nombre del campo. Para una sola imagen como string, usar {@link #imageUrl}.
     */
    @JsonAlias({"images", "photos", "pictures", "mainImage", "imageURLs"})
    private List<String> imageUrls;

    /**
     * Atajo para una sola imagen como string ({@code "imageUrl": "https://..."}). Se pliega sobre
     * {@link #imageUrls} en el caso de uso. Acepta también las claves {@code image} y {@code photo}.
     */
    @JsonAlias({"image", "photo"})
    private String imageUrl;

    /** ACTIVE (default) or DRAFT. */
    private String status;

    /** Optional stable id; generated from the title when omitted. */
    private String externalId;

    /**
     * URL de origen del producto (ficha de detalle en 1688). El operador la usa en la gestión de
     * órdenes para ir a comprar el producto al proveedor. Si se omite, se deriva del externalId
     * ({@code https://detail.1688.com/offer/<externalId>.html}).
     */
    private String sourceUrl;

    // ── Logística / aduana internacional (mapean a columnas existentes de product) ──
    /** Peso neto del producto en gramos. */
    private Integer weightGrams;
    /** Peso bruto (con embalaje) en gramos. */
    private Integer packageWeightGrams;
    private Integer lengthMm;
    private Integer widthMm;
    private Integer heightMm;
    /** País de origen (COO), código ISO-2 (ej. CN). */
    private String countryOfOrigin;
    /** Código arancelario HS. */
    private String hsCode;
    /** Certificaciones (CE, RoHS, FDA…). */
    private List<String> certifications;
    /** País/almacén de despacho, código ISO-2. */
    private String shipFrom;
    /** Plazo de despacho del proveedor, en días. */
    private Integer leadTimeDays;
    /** URL del vídeo principal. */
    private String videoUrl;

    // ── v44: campos internacionales/1688 adicionales ──
    /** Vídeos adicionales del producto. */
    private List<String> videoUrls;
    /** Regiones de venta sugeridas/autorizadas (ej. ["EU","LATAM"]). */
    private List<String> salesRegions;
    /** Desglose de reseñas por estrellas: {"5":120,"4":30,...}. */
    private Map<String, Integer> ratingBreakdown;
    /** Soporte transfronterizo: {labeling, foreignManual, foreignPackaging, boxMark}. */
    private Map<String, Object> crossBorderSupport;
    /** Unidades despachadas en 30 días (fiabilidad del proveedor). */
    private Integer dropshipShipped30d;
    /** Tasa de recolección en 48 h (0-100). */
    private BigDecimal dropshipPickupRate48h;

    /** Precios escalonados por cantidad (tiered pricing). */
    private List<BulkTier> tieredPricing;

    /** Ejes de variación (Color, Talla…) con sus valores posibles. */
    private List<BulkAxis> variantAxes;

    /** Combinaciones concretas (SKU) con stock y precio por variante. */
    private List<BulkVariant> variants;

    /** Atributos taxonómicos para facetas: [{key:"material", value:"algodón"}]. */
    private List<BulkAttr> attributes;

    /** Ficha técnica por idioma: [{locale:"es", key:"Material", value:"Algodón", position:0}]. */
    private List<BulkSpec> specifications;

    /**
     * Contenido por idioma en CUALQUIER idioma (ilimitado): {"fr": {"title":"…","description":"…"}, "ja": {…}}.
     * Complementa/override a titleEs/En/Pt/Zh; permite cargar manualmente todos los idiomas que se deseen.
     */
    private Map<String, BulkTranslation> translations;

    /** Reseñas reales del producto (cada una con su idioma). */
    private List<BulkReview> reviews;

    /** Título + descripción de un idioma. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkTranslation {
        private String title;
        private String shortDescription;
        private String description;
    }

    /** Una reseña real del producto, con su idioma. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkReview {
        private String authorName;
        private String authorCountry;
        private Integer rating;
        private String title;
        private String body;
        /** Idioma de la reseña (es/en/pt/zh…). */
        private String language;
        private Boolean verifiedPurchase;
        private List<String> tags;
    }

    /** Un tramo de precio por cantidad. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkTier {
        private Integer minQty;
        private Integer maxQty;
        private BigDecimal unitPrice;
        private String currency;
    }

    /**
     * Un eje de variación: {"name":"Color","values":["Blanco","Negro"]}. DROP-674: opcionalmente,
     * una imagen real por valor: {"valueImages":{"Blanco":"https://...","Negro":"https://..."}}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAxis {
        private String name;
        private List<String> values;
        private Map<String, String> valueImages;
        /** Traducciones por valor: {"白色1": {"es":"Blanco","en":"White"}}. */
        private Map<String, Map<String, String>> valueTranslations;
    }

    /** Una variante/SKU: {"sku":"...","optionValues":{"Color":"Blanco","Talla":"42"},"price":..,"stock":..}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkVariant {
        private String sku;
        private Map<String, String> optionValues;
        private BigDecimal price;
        private Integer stock;
        private String imageUrl;
        /** SKU del proveedor (1688) para esta combinación. */
        private String supplierSkuId;
        // DROP-675: peso/dimensiones reales POR VARIANTE (para envío). Si faltan, se usa el del producto.
        private Integer weightGrams;
        private Integer packageWeightGrams;
        private Integer lengthMm;
        private Integer widthMm;
        private Integer heightMm;
    }

    /** Un atributo taxonómico (faceta). DROP-672: {@code locale} opcional para el valor traducido. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAttr {
        private String key;
        private String value;
        /** es/en/pt/zh para un valor traducido; vacío/omitido = neutral (faceta). */
        private String locale;
    }

    /** Una fila de ficha técnica por idioma. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkSpec {
        private String locale;
        private String key;
        private String value;
        private Integer position;
    }
}
