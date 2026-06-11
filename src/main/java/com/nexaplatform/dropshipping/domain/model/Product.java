package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for a catalog product (aggregate root of the Catalog cluster).
 * Use cases operate on this model; mappers translate to/from DtoIn/DtoOut (api) and
 * the JPA entity (infra). Relations are carried flattened ({@code supplierId},
 * {@code categoryId}); sub-entities are carried as nested model lists. The
 * {@code displayTitle} / priced display fields are read-only projections filled by
 * the use case (via the {@code ProductMapper}/pricing engine) and have no entity
 * counterpart.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    private UUID id;
    private String slug;
    private String source;
    private String externalId;

    // Flattened relations (managed entities resolved by the repository adapter).
    private UUID supplierId;
    private UUID categoryId;

    // Canonical (zh) content
    private String titleZh;
    private String shortDescriptionZh;
    private String descriptionZh;

    private String brand;
    private int moq;
    private BigDecimal basePrice;
    private String currency;

    private Integer weightGrams;
    private Integer packageWeightGrams;
    private String shipFrom;
    private Boolean freeShipping;
    private Boolean selfPickup;
    private Boolean hasVideo;
    private String videoUrl;
    private Integer inventoryCount;
    private List<String> certifications;

    private ProductStatus status;
    private BigDecimal rating;
    private int reviewCount;
    private int monthlySales;
    private BigDecimal repurchaseRate;
    private BigDecimal trendScore;
    private String sourceUrl;
    private Instant ingestedAt;
    private Instant lastSyncedAt;

    // Nested sub-entities
    @Builder.Default
    private List<ProductImage> images = new ArrayList<>();
    @Builder.Default
    private List<ProductVariant> variants = new ArrayList<>();
    @Builder.Default
    private List<VariantOption> variantOptions = new ArrayList<>();
    @Builder.Default
    private List<ProductTranslation> translations = new ArrayList<>();
    @Builder.Default
    private List<ProductPriceTier> priceTiers = new ArrayList<>();

    // Read-only computed projection: resolved supplier display name (filled by use case).
    private String supplierName;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
