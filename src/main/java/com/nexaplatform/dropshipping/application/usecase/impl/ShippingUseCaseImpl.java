package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.ShippingUseCase;
import com.nexaplatform.dropshipping.domain.model.CarbonFootprint;
import com.nexaplatform.dropshipping.domain.model.ShippingRate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Shipping calculator and ESG carbon-footprint use case (DROP-13). Pure
 * computations (no persistence) that mirror the seeded rate table so the
 * calculator is offline-friendly. Holds the logic that used to live in
 * {@code PlatformExtrasService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingUseCaseImpl implements ShippingUseCase {

    @Override
    public List<ShippingRate> calculate(int weightGrams, int quantity) {
        // Synthetic estimate that mirrors our seeded rate table so the calculator is offline-friendly.
        double kg = (weightGrams * quantity) / 1000.0;
        return List.of(rate("STANDARD", "CJPacket", 3.50 + 8.0 * kg, 7, 14),
                rate("EXPRESS", "DHL Express", 12.00 + 25.0 * kg, 3, 6),
                rate("AIR", "China Post Air", 7.00 + 15.0 * kg, 5, 10),
                rate("SEA", "Sea LCL", 15.00 + 4.0 * kg, 25, 45));
    }

    @Override
    public CarbonFootprint carbonFootprint(int weightGrams, int quantity) {
        // Very rough kg CO2 estimate per kg shipped: SEA 0.1 / AIR 1.5 / EXPRESS 2.5 / STANDARD 0.4.
        double kg = (weightGrams * quantity) / 1000.0;
        BigDecimal sea = money(kg * 0.1);
        // Offset price ~ $20 per ton (industry baseline) -> $0.02 per kg CO2.
        BigDecimal offset = money(kg * 0.1 * 0.02);
        return CarbonFootprint.builder().carbonKg(sea).offsetUsd(offset).greenestMethod("SEA").build();
    }

    private static ShippingRate rate(String method, String carrier, double cost, int min, int max) {
        return ShippingRate.builder().method(method).carrier(carrier).cost(money(cost)).transitMin(min).transitMax(max)
                .build();
    }

    private static BigDecimal money(double d) {
        return new BigDecimal(d).setScale(2, RoundingMode.HALF_UP);
    }
}
