package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.WarehouseStock;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for per-warehouse product stock rows
 * ({@link WarehouseStock}). Implemented by an infrastructure adapter bridging to
 * Spring Data JPA. Distinct from the legacy Spring Data interface in
 * {@code infrastructure.persistence.repository}.
 */
public interface ProductWarehouseStockRepository extends BaseRepository<WarehouseStock, WarehouseStock, UUID> {

    /** Lists the per-warehouse stock rows of a product. */
    List<WarehouseStock> findByProductId(UUID productId);
}
