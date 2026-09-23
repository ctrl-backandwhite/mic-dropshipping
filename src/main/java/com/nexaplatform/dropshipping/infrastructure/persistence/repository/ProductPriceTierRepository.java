package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductPriceTierRepository extends JpaRepository<ProductPriceTierEntity, UUID> {
    List<ProductPriceTierEntity> findByProductIdOrderByMinQtyAsc(UUID productId);

    /**
     * Las escaleras de VARIOS productos en una sola consulta.
     *
     * <p>La cesta, la vista previa y el alta del pedido tarifican cada línea con su tramo, y pedir la
     * escalera producto a producto son tantas consultas como líneas. Con esto es una.
     */
    List<ProductPriceTierEntity> findByProductIdInOrderByMinQtyAsc(Collection<UUID> productIds);

    // Borra el tramo de precio de un producto identificado por su cantidad mínima (única por producto).
    long deleteByProductIdAndMinQty(UUID productId, int minQty);
}
