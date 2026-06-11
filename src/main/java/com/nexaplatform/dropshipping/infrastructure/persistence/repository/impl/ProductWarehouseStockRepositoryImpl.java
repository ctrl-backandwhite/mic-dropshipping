package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.WarehouseStock;
import com.nexaplatform.dropshipping.domain.repository.ProductWarehouseStockRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductWarehouseStockEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductWarehouseStockJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link ProductWarehouseStockRepository}
 * domain port on top of Spring Data JPA. Read-only port backing the per-warehouse
 * stock view of a product.
 */
@Repository
@RequiredArgsConstructor
public class ProductWarehouseStockRepositoryImpl implements ProductWarehouseStockRepository {

    private final ProductWarehouseStockEntityMapper productWarehouseStockEntityMapper;
    private final ProductWarehouseStockJpaRepositoryAdapter productWarehouseStockJpaRepositoryAdapter;

    @Override
    public List<WarehouseStock> findByProductId(UUID productId) {
        return productWarehouseStockEntityMapper.toDomainList(
                productWarehouseStockJpaRepositoryAdapter.findByProduct_Id(productId));
    }
}
