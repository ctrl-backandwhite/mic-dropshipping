package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Acceso al timeline de eventos de seguimiento de un pedido. */
public interface OrderTrackingEventRepository extends JpaRepository<OrderTrackingEventEntity, UUID> {

    List<OrderTrackingEventEntity> findByOrderIdOrderByOccurredAtAsc(UUID orderId);
}
