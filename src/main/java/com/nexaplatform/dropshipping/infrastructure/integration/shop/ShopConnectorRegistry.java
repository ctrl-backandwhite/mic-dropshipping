package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DROP-701: registry of the available {@link ShopConnector} implementations, keyed by platform code,
 * plus the canonical catalog of supported platforms. Platforms with a real connector are marked
 * {@code available=true}; the rest are announced as "coming soon" so the storefront/panel no longer
 * promise integrations that do not exist (the gap reported in DROP-701).
 */
@Component
public class ShopConnectorRegistry {

    /** Display labels for every platform the storefront advertises (order preserved). */
    private static final Map<String, String> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("shopify", "Shopify");
        CATALOG.put("woocommerce", "WooCommerce");
        CATALOG.put("tiktokshop", "TikTok Shop");
        CATALOG.put("ebay", "eBay");
        CATALOG.put("amazon", "Amazon");
        CATALOG.put("etsy", "Etsy");
        CATALOG.put("bigcommerce", "BigCommerce");
        CATALOG.put("wix", "Wix");
        CATALOG.put("squarespace", "Squarespace");
        CATALOG.put("magento", "Magento");
        CATALOG.put("lazada", "Lazada");
        CATALOG.put("shopee", "Shopee");
    }

    private final Map<String, ShopConnector> byPlatform = new LinkedHashMap<>();

    public ShopConnectorRegistry(List<ShopConnector> connectors) {
        for (ShopConnector c : connectors) {
            byPlatform.put(c.platform().toLowerCase(), c);
        }
    }

    /** Connector for a platform code, if one is registered and available. */
    public Optional<ShopConnector> connectorFor(String platform) {
        if (platform == null) {
            return Optional.empty();
        }
        ShopConnector c = byPlatform.get(platform.toLowerCase());
        return c != null && c.available() ? Optional.of(c) : Optional.empty();
    }

    /** Whether a given platform has a real, available connector. */
    public boolean isAvailable(String platform) {
        return connectorFor(platform).isPresent();
    }

    /** The full platform catalog with honest availability flags (DROP-701). */
    public List<ShopPlatform> catalog() {
        return CATALOG.entrySet().stream().map(e -> ShopPlatform.builder().code(e.getKey()).label(e.getValue())
                .available(isAvailable(e.getKey())).build()).toList();
    }
}
