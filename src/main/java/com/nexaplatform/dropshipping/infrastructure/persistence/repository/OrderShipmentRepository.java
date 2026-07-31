package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a los bultos (guías) en los que se reparte un pedido. */
public interface OrderShipmentRepository extends JpaRepository<OrderShipmentEntity, UUID> {

    List<OrderShipmentEntity> findByOrderIdOrderBySequenceNoAsc(UUID orderId);

    /** Resuelve el bulto por la guía del transportista, que es como llega identificado en sus pushes. */
    Optional<OrderShipmentEntity> findByWaybillNumber(String waybillNumber);

    Optional<OrderShipmentEntity> findByTrackingNumber(String trackingNumber);
}
