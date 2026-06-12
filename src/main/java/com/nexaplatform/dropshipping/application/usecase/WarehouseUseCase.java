package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.domain.model.WarehouseStock;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the warehouse aggregate (DROP-13): the active-warehouse
 * listing plus the per-warehouse stock view of a product. Operates on the
 * {@link Warehouse} / {@link WarehouseStock} domain models.
 */
public interface WarehouseUseCase {

    /** Lists the active warehouses (ordered by country). */
    List<Warehouse> listActive();

    /** Lists the per-warehouse stock rows of a product. */
    List<WarehouseStock> stockPerWarehouse(UUID productId);

    /** Admin: lists every warehouse (active and inactive). */
    List<Warehouse> listAll();

    /** Admin: gets one warehouse by id (404 if missing). */
    Warehouse getById(UUID id);

    /** Admin: creates a warehouse (unique code). */
    Warehouse create(Warehouse model);

    /** Admin: updates a warehouse (unique code). */
    Warehouse update(UUID id, Warehouse model);

    /** Admin: deletes a warehouse. */
    void delete(UUID id);
}
