package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transport representation of a partner order. Field names mirror the legacy
 * {@code OrderView} record so the frontend contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerOrderDtoOut {

    @Schema(description = "Order identifier")
    private UUID id;

    @Schema(description = "Human-readable order number")
    private String orderNumber;

    @Schema(description = "Order status")
    private String status;

    @Schema(description = "Items subtotal")
    private BigDecimal subtotal;

    @Schema(description = "Shipping cost")
    private BigDecimal shipping;

    @Schema(description = "Tax amount")
    private BigDecimal tax;

    @Schema(description = "Order total")
    private BigDecimal total;

    @Schema(description = "Currency code")
    private String currency;

    @Schema(description = "When the order was placed")
    private Instant placedAt;

    @Schema(description = "When the order was shipped (null if not shipped)")
    private Instant shippedAt;

    @Schema(description = "Order line items")
    private List<PartnerOrderItemDtoOut> items;
}
