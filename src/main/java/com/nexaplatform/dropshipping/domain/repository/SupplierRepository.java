package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Supplier;

import java.util.UUID;

/**
 * Domain repository port for {@link Supplier}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface SupplierRepository extends BaseRepository<Supplier, Supplier, UUID> {
}
