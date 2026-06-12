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
        // DROP-629: the product's headline price must be traceable to a real, purchasable
        // variant — not the disconnected base_price. When no specific variant is requested
        // (catalog list / detail headline) and the product has active variants, derive the
        // price from the representative (cheapest active) variant. A single-variant product
        // therefore prices exactly as its variant (delta 0%), and multi-variant products show
        // the "from" price that the customer can actually pay.
        ProductVariantEntity effective = variant != null ? variant : representativeVariant(product);
        BigDecimal supplierAmount = effective != null && effective.getPrice() != null
                ? effective.getPrice()
                : product.getBasePrice();
        String sourceCurrency = product.getCurrency() != null ? product.getCurrency() : "CNY";
        BigDecimal costUsd = supplierAmount != null ? currencyService.toUsd(supplierAmount, sourceCurrency) : null;
        var withMargin = marginService.apply(costUsd, product, effective);
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

    /**
     * DROP-629: representative variant used to price the product headline — the cheapest
     * active variant with a real price. Returns {@code null} (→ fall back to base_price) when
     * there are no priced active variants, or when variants are lazily detached outside a
     * transaction (defensive: pricing is also called from list/search contexts).
     */
    private ProductVariantEntity representativeVariant(ProductEntity product) {
        if (product == null) {
            return null;
        }
        try {
            var variants = product.getVariants();
            if (variants == null || variants.isEmpty()) {
                return null;
            }
            return variants.stream()
                    .filter(v -> v != null && v.isActive() && v.getPrice() != null && v.getPrice().signum() > 0)
                    .min(java.util.Comparator.comparing(ProductVariantEntity::getPrice))
                    .orElse(null);
        } catch (RuntimeException lazyOutsideTx) {
            return null;
        }
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
