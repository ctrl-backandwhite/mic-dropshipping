package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Per-warehouse stock row for a product.
 */
@Value
@Builder
public class WarehouseStockDtoOut {

    UUID warehouseId;
    String warehouseCode;
    String country;
    int stock;
}
