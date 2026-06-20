package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Order}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface with the same simple name in
 * {@code infrastructure.persistence.repository} (kept for out-of-cluster consumers).
 */
public interface OrderRepository extends BaseRepository<Order, Order, UUID> {

    /** Looks up an order by its unique human-facing order number. */
    Optional<Order> findByOrderNumber(String orderNumber);

    /** Looks up an order by its carrier tracking number (used by inbound Cainiao webhooks). */
    Optional<Order> findByTrackingNumber(String trackingNumber);

    /** Orders placed through the given partner application. */
    List<Order> findByPartnerAppId(UUID partnerAppId);

    /** Single order by id as an {@link Optional} (ownership checks in the use case). */
    Optional<Order> findById(UUID id);
}
