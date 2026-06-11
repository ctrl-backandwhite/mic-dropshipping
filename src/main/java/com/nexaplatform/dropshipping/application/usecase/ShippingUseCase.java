package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.CarbonFootprint;
import com.nexaplatform.dropshipping.domain.model.ShippingRate;

import java.util.List;

/**
 * Use-case port for the DROP-13 shipping calculator and ESG carbon-footprint
 * estimator. Pure computations (no persistence) producing the
 * {@link ShippingRate} / {@link CarbonFootprint} domain models from the shipment
 * weight and quantity.
 */
public interface ShippingUseCase {

    /** Estimates the shipping rates for the given shipment weight/quantity. */
    List<ShippingRate> calculate(int weightGrams, int quantity);

    /** Estimates the carbon footprint of the given shipment weight/quantity. */
    CarbonFootprint carbonFootprint(int weightGrams, int quantity);
}
