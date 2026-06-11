package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * One row of the shipping calculator estimate.
 */
@Value
@Builder
public class ShippingRateDtoOut {

    String method;
    String carrier;
    BigDecimal cost;
    int transitMin;
    int transitMax;
}
