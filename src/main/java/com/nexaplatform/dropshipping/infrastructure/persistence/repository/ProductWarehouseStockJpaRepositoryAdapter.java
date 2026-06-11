package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductWarehouseStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code ProductWarehouseStockRepository} domain port. */
public interface ProductWarehouseStockJpaRepositoryAdapter extends JpaRepository<ProductWarehouseStockEntity, UUID> {

    List<ProductWarehouseStockEntity> findByProduct_Id(UUID productId);
}
