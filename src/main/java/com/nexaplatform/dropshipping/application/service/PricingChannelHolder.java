package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;

/**
 * Canal de precios del request actual (ThreadLocal), análogo a {@code CurrencyHolder}.
 *
 * <p>Por defecto {@link PriceRuleChannel#STOREFRONT} (tienda propia). Las integraciones (Shopify,
 * WooCommerce, API de partners) lo ponen a {@link PriceRuleChannel#INTEGRATION} para que
 * {@code MarginService} resuelva la regla de margen del canal correcto (p. ej. 75% en integración vs
 * 150% en storefront). Se limpia al final del request.
 */
public final class PricingChannelHolder {

    private static final ThreadLocal<PriceRuleChannel> CURRENT = ThreadLocal
            .withInitial(() -> PriceRuleChannel.STOREFRONT);

    private PricingChannelHolder() {
    }

    public static PriceRuleChannel get() {
        PriceRuleChannel c = CURRENT.get();
        return c == null ? PriceRuleChannel.STOREFRONT : c;
    }

    public static void set(PriceRuleChannel channel) {
        CURRENT.set(channel == null ? PriceRuleChannel.STOREFRONT : channel);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
