package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All Request + Response records for catalog endpoints. */
public final class CatalogDtos {
    private CatalogDtos() {
    }

    /* ============================ REQUESTS ============================ */

    public record IngestProductRequest(@NotBlank String source, @NotBlank String externalId, @NotBlank String titleZh,
            String shortDescriptionZh, String descriptionZh, String brand, Integer moq, BigDecimal basePrice,
            String currency, Integer weightGrams, Integer monthlySales, BigDecimal repurchaseRate, BigDecimal rating,
            Integer reviewCount, String sourceUrl, UUID supplierId, UUID categoryId, List<IngestImage> images,
            List<IngestVariantOption> options, List<IngestVariant> variants, List<IngestPriceTier> priceTiers) {
    }

    public record IngestImage(@NotBlank String sourceUrl, int position, String role) {
    }

    public record IngestVariantOption(@NotBlank String nameZh, int position, List<IngestVariantValue> values) {
    }

    public record IngestVariantValue(@NotBlank String valueZh, int position, String imageSourceUrl) {
    }

    public record IngestVariant(String externalId, String sku, String title, BigDecimal price, Integer stock,
            String imageSourceUrl, Map<String, String> options) {
    }

    public record IngestPriceTier(@Positive int minQty, Integer maxQty, BigDecimal unitPrice, String currency) {
    }

    public record IngestSupplierRequest(@NotBlank String source, @NotBlank String externalId, String name,
            String nameZh, String country, String city, BigDecimal rating, Integer yearsActive, boolean verified,
            boolean trustPass, String profileUrl) {
    }

    public record IngestCategoryRequest(@NotBlank String slug, UUID parentId, String source, String externalId,
            String nameZh, int position, String icon, Map<String, String> nameTranslations) {
    }

    public record UpdateProductStatusRequest(@NotNull String status) {
    }

    /* ============================ RESPONSES ============================ */

    public record SupplierView(UUID id, String source, String externalId, String name, String nameZh, String country,
            String city, BigDecimal rating, Integer yearsActive, boolean verified, boolean trustPass,
            String profileUrl) {
    }

    public record CategoryView(UUID id, String slug, String nameZh, Map<String, String> names, UUID parentId,
            int position, String icon, boolean active) {
    }

    public record CategoryTreeView(UUID id, String slug, String nameZh, Map<String, String> names, String icon,
            List<CategoryTreeView> children) {
    }

    public record ProductImageView(UUID id, int position, String role, String sourceUrl, String cdnUrl) {
    }

    /**
     * Valor de un eje de variante. El campo {@code value} es el override neutral que se mantiene por
     * compatibilidad; {@code valueLocalized} es la etiqueta del idioma activo, que cae al override
     * cuando no hay traducción; y {@code translations} lleva todas las traducciones por idioma, que es
     * lo que necesita el editor del admin.
     */
    public record VariantValueView(UUID id, String valueZh, String value, String valueLocalized, String imageUrl,
            String imageSourceUrl, int position, Map<String, String> translations) {
    }

    public record VariantOptionView(UUID id, String nameZh, String name, int position, List<VariantValueView> values) {
    }

    public record VariantView(UUID id, String sku, String title, BigDecimal price, String priceFormatted, int stock,
            String imageUrl, Map<String, String> options, boolean active,
            // Báscula de peso por variante (peso en gramos, dimensiones en mm). El volumen se calcula en el front.
            Integer weightGrams, Integer lengthMm, Integer widthMm, Integer heightMm) {
    }

    public record PriceTierView(int minQty, Integer maxQty, BigDecimal unitPrice, String currency,
            String unitPriceFormatted) {
    }

    public record ProductSummaryView(UUID id, String slug, String title, String mainImage, BigDecimal basePrice, // legacy display in CNY (kept for back-compat)
            String currency, // legacy CNY label
            BigDecimal rating, int monthlySales, BigDecimal trendScore, String status,
            // canonical + display
            BigDecimal priceUsd, // retail in USD canon
            BigDecimal displayPrice, // converted to user's currency (X-Currency)
            String displayCurrency, String displaySymbol,
            String displayFormatted, // string ya formateado por el backend ("28,26 €") — el front solo lo pinta
            // stock: inventoryCount es el rollup del proveedor; availableUnits es
            // la suma del stock por variantes activas (más fiel para fulfillment).
            Integer inventoryCount, Integer availableUnits,
            // verificación manual del admin (false = pendiente/con error, true = revisado OK)
            boolean verified,
            // Rebaja. Nulos cuando el producto no está en promoción: es lo que decide si el escaparate
            // pinta el precio anterior tachado o solo uno. Ya vienen formateados por el backend.
            String originalFormatted, Integer discountPercent, String promotionName) {

        /** Sin promoción: atajo para los usos que no la calculan. */
        public ProductSummaryView(UUID id, String slug, String title, String mainImage, BigDecimal basePrice,
                String currency, BigDecimal rating, int monthlySales, BigDecimal trendScore, String status,
                BigDecimal priceUsd, BigDecimal displayPrice, String displayCurrency, String displaySymbol,
                String displayFormatted, Integer inventoryCount, Integer availableUnits, boolean verified) {
            this(id, slug, title, mainImage, basePrice, currency, rating, monthlySales, trendScore, status,
                    priceUsd, displayPrice, displayCurrency, displaySymbol, displayFormatted, inventoryCount,
                    availableUnits, verified, null, null, null);
        }
    }

    public record ProductDetailView(UUID id, String slug, String source, String externalId, SupplierView supplier,
            UUID categoryId, String title, String shortDescription, String description, String titleZh,
            String shortDescriptionZh, String descriptionZh, String brand, int moq, BigDecimal basePrice,
            String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal repurchaseRate,
            BigDecimal trendScore, String status, String sourceUrl, Instant ingestedAt, Instant lastSyncedAt,
            List<ProductImageView> images, List<VariantOptionView> variantOptions, List<VariantView> variants,
            List<PriceTierView> priceTiers,
            // pricing
            BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayPrice, String displayCurrency,
            String displaySymbol, String displayFormatted, BigDecimal appliedMarginPercent,
            // Desglose del total (base×margen + IVA + envío). SOLO ADMIN (null para usuario final); el
            // displayFormatted ya es el TOTAL que ve todo el mundo.
            String baseFormatted, String ivaFormatted, String shippingFormatted,
            // DROP-679: SEO por idioma (generado al publicar a partir del contenido real)
            String metaTitle, String metaDescription,
            // verificación manual del admin (false = pendiente/con error, true = revisado OK)
            boolean verified,
            // Vídeo de explicación del producto (columna product.video_url). El front lo muestra en la galería.
            String videoUrl, boolean hasVideo,
            // Rebaja vigente sobre este producto. Nulos si no la hay.
            String originalFormatted, Integer discountPercent, String promotionName) {

        /** Sin promoción: atajo para los usos que no la calculan. */
        public ProductDetailView(UUID id, String slug, String source, String externalId, SupplierView supplier,
                UUID categoryId, String title, String shortDescription, String description, String titleZh,
                String shortDescriptionZh, String descriptionZh, String brand, int moq, BigDecimal basePrice,
                String currency, BigDecimal rating, int reviewCount, int monthlySales, BigDecimal repurchaseRate,
                BigDecimal trendScore, String status, String sourceUrl, Instant ingestedAt, Instant lastSyncedAt,
                List<ProductImageView> images, List<VariantOptionView> variantOptions, List<VariantView> variants,
                List<PriceTierView> priceTiers, BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayPrice,
                String displayCurrency, String displaySymbol, String displayFormatted,
                BigDecimal appliedMarginPercent, String baseFormatted, String ivaFormatted,
                String shippingFormatted, String metaTitle, String metaDescription, boolean verified,
                String videoUrl, boolean hasVideo) {
            this(id, slug, source, externalId, supplier, categoryId, title, shortDescription, description,
                    titleZh, shortDescriptionZh, descriptionZh, brand, moq, basePrice, currency, rating,
                    reviewCount, monthlySales, repurchaseRate, trendScore, status, sourceUrl, ingestedAt,
                    lastSyncedAt, images, variantOptions, variants, priceTiers, costUsd, retailUsd, displayPrice,
                    displayCurrency, displaySymbol, displayFormatted, appliedMarginPercent, baseFormatted,
                    ivaFormatted, shippingFormatted, metaTitle, metaDescription, verified, videoUrl, hasVideo,
                    null, null, null);
        }
    }

    public record BestsellerView(UUID productId, String slug, String title, String mainImage, int rank, String listCode,
            Instant capturedAt) {
    }
}
