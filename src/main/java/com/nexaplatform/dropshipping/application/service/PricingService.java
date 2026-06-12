package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Canonical pricing pipeline:
 *
 * <pre>
 *   supplierPrice (in product.currency, e.g. CNY)
 *      → costUsd        (CurrencyRateService.toUsd)
 *      → retailUsd      (MarginService.apply: rule-resolved markup)
 *      → displayPrice   (CurrencyRateService.usdToDisplay: convert to user's currency via X-Currency header)
 * </pre>
 *
 * Stripe always charges in USD using {@code retailUsd}; the display number is purely
 * presentational on the store / partner API.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final CurrencyRateService currencyService;
    private final MarginService marginService;

    public PricedAmount priceFor(ProductEntity product, ProductVariantEntity variant) {
        BigDecimal supplierAmount = variant != null && variant.getPrice() != null
                ? variant.getPrice()
                : product.getBasePrice();
        String sourceCurrency = product.getCurrency() != null ? product.getCurrency() : "CNY";
        BigDecimal costUsd = supplierAmount != null ? currencyService.toUsd(supplierAmount, sourceCurrency) : null;
        var withMargin = marginService.apply(costUsd, product, variant);
        BigDecimal retailUsd = withMargin.retailUsd();
        String displayCode = CurrencyHolder.get();
        BigDecimal displayAmount = currencyService.usdToDisplay(retailUsd);
        return new PricedAmount(costUsd, retailUsd, displayAmount, displayCode, currencyService.symbolOf(displayCode),
                withMargin.appliedRule() != null ? withMargin.appliedRule().getId() : null,
                withMargin.appliedPercentage());
    }

    public PricedAmount priceFor(ProductEntity product) {
        return priceFor(product, null);
    }

    public BigDecimal convertUsdToDisplay(BigDecimal amountUsd) {
        return currencyService.usdToDisplay(amountUsd);
    }

    public String displayCurrencyCode() {
        return CurrencyHolder.get();
    }

    public String displayCurrencySymbol() {
        return currencyService.symbolOf(CurrencyHolder.get());
    }

    public record PricedAmount(BigDecimal costUsd, BigDecimal retailUsd, BigDecimal displayAmount,
            String displayCurrency, String displaySymbol, java.util.UUID appliedRuleId,
            BigDecimal appliedMarginPercent) {
    }
}
