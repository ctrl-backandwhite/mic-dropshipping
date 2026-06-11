package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.ShopConnection;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link ShopConnection}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface ShopConnectionRepository extends BaseRepository<ShopConnection, ShopConnection, UUID> {

    /** Lists the connections owned by a user, newest first (legacy admin-list ordering). */
    List<ShopConnection> findByUserId(UUID userId);
}
