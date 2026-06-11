package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the user's shop integrations; operates on the
 * {@link ShopConnection} aggregate and its {@link ShopProductListing} sub-entity.
 * Every operation is scoped to the authenticated {@code userId} (ownership check).
 */
public interface ShopConnectionUseCase extends BaseUseCase<ShopConnection, ShopConnection, UUID> {

    /** Lists the connected shops owned by the user, with the listings count filled. */
    List<ShopConnection> listByUser(UUID userId);

    /** Connects a new shop integration owned by the user (raw access token gets encrypted). */
    ShopConnection connect(UUID userId, ShopConnection model);

    /** Triggers a sync for a connected shop owned by the user, stamping the last-sync time. */
    ShopConnection sync(UUID userId, UUID id);

    /** Disconnects (deletes) a shop integration owned by the user. */
    void disconnect(UUID userId, UUID id);

    /** Emits (or rotates) the inbound HMAC secret used to sign shop order webhooks. */
    ShopInboundSecret rotateInboundSecret(UUID userId, UUID id);

    /** Lists a catalog product into a shop owned by the user (upsert by shop + product). */
    ShopProductListing listProduct(UUID userId, UUID id, UUID productId);

    /** Lists the product listings of a shop owned by the user. */
    List<ShopProductListing> listings(UUID userId, UUID id);

    /** Returns the static catalog of supported shop platforms. */
    List<ShopPlatform> platforms();
}
