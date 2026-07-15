package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductPriceTierRepository extends JpaRepository<ProductPriceTierEntity, UUID> {
    List<ProductPriceTierEntity> findByProductIdOrderByMinQtyAsc(UUID productId);

    // Borra el tramo de precio de un producto identificado por su cantidad mínima (única por producto).
    long deleteByProductIdAndMinQty(UUID productId, int minQty);
}
