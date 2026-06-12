package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.domain.repository.WarehouseRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WarehouseEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WarehouseJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link WarehouseRepository} domain port
 * on top of Spring Data JPA. {@code findActive} preserves the legacy storefront
 * contract (active warehouses ordered by country).
 */
@Repository
@RequiredArgsConstructor
public class WarehouseRepositoryImpl implements WarehouseRepository {

    private final WarehouseEntityMapper warehouseEntityMapper;
    private final WarehouseJpaRepositoryAdapter warehouseJpaRepositoryAdapter;

    @Override
    public Warehouse save(Warehouse model) {
        var entity = warehouseJpaRepositoryAdapter.save(warehouseEntityMapper.toEntity(model));
        return warehouseEntityMapper.toDomain(entity);
    }

    @Override
    public List<Warehouse> findActive() {
        return warehouseEntityMapper.toDomainList(warehouseJpaRepositoryAdapter.findByActiveTrueOrderByCountryAsc());
    }

    @Override
    public Warehouse getById(UUID id) {
        return warehouseJpaRepositoryAdapter.findById(id).map(warehouseEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        warehouseJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return warehouseJpaRepositoryAdapter.existsById(id);
    }
}
