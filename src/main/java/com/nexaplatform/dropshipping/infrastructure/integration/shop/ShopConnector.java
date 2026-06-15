package com.nexaplatform.dropshipping.infrastructure.integration.shop;

import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;

/**
 * DROP-701: port for a real e-commerce platform connector. Each implementation pushes a catalog
 * product into the connected external store (Shopify, WooCommerce…) and reports the outcome. The
 * registry picks the connector by {@link #platform()}; platforms without an {@code available}
 * connector are surfaced as "coming soon" in the storefront/panel (closing the marketing-vs-reality
 * gap reported in DROP-701) and produce a clear error instead of failing silently (DROP-693).
 */
public interface ShopConnector {

    /** Lowercase platform code this connector handles (e.g. {@code shopify}, {@code woocommerce}). */
    String platform();

    /** Whether the integration is fully implemented (vs. announced as "coming soon"). */
    boolean available();

    /**
     * Publishes (or upserts) the given product into the external store.
     *
     * @param shop           the connection (handle + status; ownership already checked)
     * @param decryptedToken the plaintext access token / credentials for the store
     * @param product        the catalog product to publish
     * @return a {@link PushResult} carrying the remote id on success, or a human-readable error
     */
    PushResult push(ShopConnection shop, String decryptedToken, ProductEntity product);

    /** Outcome of a push: ok + remoteProductId, or a failure with an error message. */
    record PushResult(boolean ok, String remoteProductId, String error) {
        public static PushResult ok(String remoteProductId) {
            return new PushResult(true, remoteProductId, null);
        }

        public static PushResult fail(String error) {
            return new PushResult(false, null, error);
        }
    }
}
