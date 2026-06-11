package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.UUID;

/**
 * Read-only domain model for a per-warehouse stock row of a product. Carries the
 * flattened warehouse identity ({@code warehouseId}, {@code warehouseCode},
 * {@code country}) the storefront view exposes; mapped to the transport
 * {@code WarehouseStockDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseStock {

    private UUID warehouseId;
    private String warehouseCode;
    private String country;
    private int stock;
}
