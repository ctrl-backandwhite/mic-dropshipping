package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/**
 * Read-only domain model for one row of the shipping calculator estimate. Built
 * by the query use case; mapped to the transport {@code ShippingRateDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingRate {

    private String method;
    private String carrier;
    private BigDecimal cost;
    private int transitMin;
    private int transitMax;
}
