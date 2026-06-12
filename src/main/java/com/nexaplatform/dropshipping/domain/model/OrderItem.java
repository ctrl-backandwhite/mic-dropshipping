package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Map;
import java.util.UUID;

/**
 * Pure domain model for an order line (nested sub-entity of {@link Order}).
 * Carries flattened product/variant ids resolved to managed relations by the
 * repository adapter, plus the snapshot fields persisted at checkout time.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    private UUID id;
    private UUID productId;
    private UUID variantId;
    private String titleSnapshot;
    private String imageUrlSnapshot;
    private String skuSnapshot;
    private int unitPriceCents;
    private int costCents;
    private int quantity;
    private int lineTotalCents;

    // Read-only resolved fields (filled by the repository adapter from the managed
    // product/variant relations) so the api mappers can build line details without
    // touching the entity.
    private String productTitleZh;
    private String variantName;
    private String supplierName;

    // Live catalog image (cdnUrl preferred over sourceUrl), resolved by the adapter.
    private String productImageUrl;

    // Language -> product translation title, resolved by the adapter; the use case
    // picks the request-language title with the legacy fallback chain.
    private Map<String, String> productTitles;
}
