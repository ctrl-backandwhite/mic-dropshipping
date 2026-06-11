package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.WarehouseUseCase;
import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.domain.model.WarehouseStock;
import com.nexaplatform.dropshipping.domain.repository.ProductWarehouseStockRepository;
import com.nexaplatform.dropshipping.domain.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Warehouse use case (DROP-13). Operates on the {@link Warehouse} /
 * {@link WarehouseStock} models and delegates persistence to the domain ports.
 * Holds the logic that used to live in {@code PlatformExtrasService}: the
 * active-warehouse listing and the per-warehouse stock view of a product.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseUseCaseImpl implements WarehouseUseCase {

    private final WarehouseRepository warehouseRepository;
    private final ProductWarehouseStockRepository productWarehouseStockRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Warehouse> listActive() {
        return warehouseRepository.findActive();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseStock> stockPerWarehouse(UUID productId) {
        return productWarehouseStockRepository.findByProductId(productId);
    }
}
