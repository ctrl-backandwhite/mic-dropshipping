package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.UUID;

/** Nested sub-entity of {@link Product}: a B2B quantity price tier. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPriceTier {

    private UUID id;
    private int minQty;
    private Integer maxQty;
    private BigDecimal unitPrice;
    private String currency;
    /** Recargo fijo de este tramo. Nulo = hereda el del producto. */
    private BigDecimal surchargeCny;
}
