package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
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

    @Override
    @Transactional(readOnly = true)
    public List<Warehouse> listAll() {
        return warehouseRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Warehouse getById(UUID id) {
        Warehouse w = warehouseRepository.getById(id);
        if (w == null) {
            throw new NotFoundException("Almacén no encontrado");
        }
        return w;
    }

    @Override
    @Transactional
    public Warehouse create(Warehouse model) {
        if (codeTaken(model.getCode(), null)) {
            throw new ConflictException("Ya existe un almacén con código " + model.getCode());
        }
        return warehouseRepository.save(model);
    }

    @Override
    @Transactional
    public Warehouse update(UUID id, Warehouse model) {
        Warehouse existing = getById(id);
        if (model.getCode() != null && codeTaken(model.getCode(), id)) {
            throw new ConflictException("Ya existe un almacén con código " + model.getCode());
        }
        if (model.getCode() != null)
            existing.setCode(model.getCode());
        if (model.getName() != null)
            existing.setName(model.getName());
        if (model.getCountry() != null)
            existing.setCountry(model.getCountry());
        if (model.getCity() != null)
            existing.setCity(model.getCity());
        existing.setActive(model.isActive());
        return warehouseRepository.update(existing);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        warehouseRepository.delete(id);
    }

    private boolean codeTaken(String code, UUID excludeId) {
        if (code == null)
            return false;
        return warehouseRepository.findAll().stream().anyMatch(
                w -> code.equalsIgnoreCase(w.getCode()) && (excludeId == null || !excludeId.equals(w.getId())));
    }
}
