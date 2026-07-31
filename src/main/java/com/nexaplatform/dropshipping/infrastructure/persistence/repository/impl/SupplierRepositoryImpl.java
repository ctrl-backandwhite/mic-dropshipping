package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.domain.model.Supplier;
import com.nexaplatform.dropshipping.domain.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupplierEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SupplierRepository} domain port
 * on top of Spring Data JPA. {@code findAll} preserves the legacy contract of the
 * admin list (all suppliers, default ordering).
 */
@Repository
@RequiredArgsConstructor
public class SupplierRepositoryImpl implements SupplierRepository {

    private final SupplierEntityMapper supplierEntityMapper;
    private final SupplierJpaRepositoryAdapter supplierJpaRepositoryAdapter;

    @Override
    public Supplier save(Supplier model) {
        SupplierEntity entity = supplierJpaRepositoryAdapter.save(supplierEntityMapper.toEntity(model));
        return supplierEntityMapper.toDomain(entity);
    }

    @Override
    public List<Supplier> findAll() {
        return supplierEntityMapper.toDomainList(supplierJpaRepositoryAdapter.findAll());
    }

    @Override
    public Supplier update(Supplier model) {
        return this.save(model);
    }

    @Override
    public Supplier getById(UUID id) {
        return supplierJpaRepositoryAdapter.findById(id).map(supplierEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        supplierJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return supplierJpaRepositoryAdapter.existsById(id);
    }
}
