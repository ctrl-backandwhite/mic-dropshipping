package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<CustomerOrderEntity, UUID> {
    Optional<CustomerOrderEntity> findByOrderNumber(String orderNumber);

    List<CustomerOrderEntity> findByPartnerAppId(UUID partnerAppId);

    /** Orden reutilizable del mismo carrito (mismo idem) aún sin pagar, para no duplicar. */
    Optional<CustomerOrderEntity> findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(
            UUID userId, String idempotencyKey, Collection<OrderStatus> statuses);
}
