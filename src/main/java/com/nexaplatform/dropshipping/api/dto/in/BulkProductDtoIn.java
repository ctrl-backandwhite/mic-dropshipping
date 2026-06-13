package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product row of a bulk JSON import. Friendly, flat shape (the heavy
 * {@code IngestProductRequest} is built server-side). The category is referenced
 * by slug; the supplier is optional (a default is picked when omitted).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkProductDtoIn {

    /** Category slug the product belongs to (must already exist). */
    @NotBlank
    private String categorySlug;

    @NotBlank
    private String titleEs;

    private String titleEn;

    private String titlePt;

    private String titleZh;

    private String descriptionEs;

    private String descriptionEn;

    private String descriptionPt;

    private String descriptionZh;

    private BigDecimal price;

    private Integer moq;

    private Integer monthlySales;

    private BigDecimal rating;

    /** Optional supplier external id (1688); first available supplier used when null. */
    private String supplierExternalId;

    /** Optional product manufacturer (stored in the product's brand field). */
    private String manufacturer;

    private List<String> imageUrls;

    /** ACTIVE (default) or DRAFT. */
    private String status;

    /** Optional stable id; generated from the title when omitted. */
    private String externalId;

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

    /** Precios escalonados por cantidad (tiered pricing). */
    private List<BulkTier> tieredPricing;

    /** Ejes de variación (Color, Talla…) con sus valores posibles. */
    private List<BulkAxis> variantAxes;

    /** Combinaciones concretas (SKU) con stock y precio por variante. */
    private List<BulkVariant> variants;

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

    /** Un eje de variación: {"name":"Color","values":["Blanco","Negro"]}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkAxis {
        private String name;
        private List<String> values;
    }

    /** Una variante/SKU: {"sku":"...","optionValues":{"Color":"Blanco","Talla":"42"},"price":..,"stock":..}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkVariant {
        private String sku;
        private java.util.Map<String, String> optionValues;
        private BigDecimal price;
        private Integer stock;
        private String imageUrl;
    }
}
