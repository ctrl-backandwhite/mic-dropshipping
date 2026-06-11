package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Transport representation of a single line item in a partner order.
 * Field names mirror the legacy {@code OrderItemView} record so the frontend
 * contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerOrderItemDtoOut {

    @Schema(description = "Product identifier")
    private UUID productId;

    @Schema(description = "Variant identifier (null when no variant)")
    private UUID variantId;

    @Schema(description = "Ordered quantity")
    private int quantity;

    @Schema(description = "Unit price")
    private BigDecimal unitPrice;

    @Schema(description = "Line total (unit price x quantity)")
    private BigDecimal lineTotal;
}
