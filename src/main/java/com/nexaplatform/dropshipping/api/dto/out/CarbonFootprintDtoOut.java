package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * ESG carbon-footprint estimate for a shipment.
 */
@Value
@Builder
public class CarbonFootprintDtoOut {

    BigDecimal carbonKg;
    BigDecimal offsetUsd;
    String greenestMethod;
}
