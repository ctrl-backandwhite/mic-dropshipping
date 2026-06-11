package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Warehouse;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link Warehouse}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface WarehouseRepository extends BaseRepository<Warehouse, Warehouse, UUID> {

    /** Lists the active warehouses ordered by country. */
    List<Warehouse> findActive();
}
