package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

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
}
