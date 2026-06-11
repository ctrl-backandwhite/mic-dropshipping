package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/**
 * Read-only domain model for an ESG carbon-footprint estimate of a shipment.
 * Built by the query use case; mapped to the transport {@code CarbonFootprintDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarbonFootprint {

    private BigDecimal carbonKg;
    private BigDecimal offsetUsd;
    private String greenestMethod;
}
