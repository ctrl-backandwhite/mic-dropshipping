package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Nested sub-entity of {@link Product}: a purchasable variant (SKU). */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductVariant {

    private UUID id;
    private String externalId;
    private String sku;
    private String title;
    private BigDecimal price;
    private int stock;
    private Integer weightGrams;
    private String barcode;
    private String imageSourceUrl;
    private String imageCdnUrl;
    private Map<String, String> options;
    private boolean active;
}
