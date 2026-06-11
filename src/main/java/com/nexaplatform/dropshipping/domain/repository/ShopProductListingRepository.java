package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.ShopProductListing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for the {@link ShopProductListing} sub-entity of the
 * {@link com.nexaplatform.dropshipping.domain.model.ShopConnection} aggregate.
 * Implemented by an infrastructure adapter bridging to Spring Data JPA. Distinct
 * from the legacy Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface ShopProductListingRepository extends BaseRepository<ShopProductListing, ShopProductListing, UUID> {

    /** Lists every product listing of a connected shop. */
    List<ShopProductListing> findByShopConnectionId(UUID shopConnectionId);

    /** Counts the product listings of a connected shop. */
    int countByShopConnectionId(UUID shopConnectionId);

    /** Looks up the listing of a product within a shop (used to upsert on list-product). */
    Optional<ShopProductListing> findByShopConnectionIdAndProductId(UUID shopConnectionId, UUID productId);
}
