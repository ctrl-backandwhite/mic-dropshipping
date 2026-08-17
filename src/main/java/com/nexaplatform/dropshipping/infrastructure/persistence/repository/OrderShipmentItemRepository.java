package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderShipmentItemRepository extends JpaRepository<OrderShipmentItemEntity, UUID> {

    List<OrderShipmentItemEntity> findByShipmentId(UUID shipmentId);

    /** El seguimiento pinta todos los bultos de un pedido: se piden sus contenidos de una vez. */
    List<OrderShipmentItemEntity> findByShipmentIdIn(Collection<UUID> shipmentIds);
}
