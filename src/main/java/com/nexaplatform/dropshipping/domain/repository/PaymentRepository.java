package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Payment}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface with the same simple name in
 * {@code infrastructure.persistence.repository} (kept for out-of-cluster consumers).
 */
public interface PaymentRepository extends BaseRepository<Payment, Payment, UUID> {

    /** Single payment by id as an {@link Optional} (use-case guards). */
    Optional<Payment> findById(UUID id);

    /** Idempotent lookup: returns the existing payment for a given idempotency key. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Resolve a payment from its provider + provider reference (webhook dispatch). */
    Optional<Payment> findByProviderAndProviderRef(String provider, String providerRef);

    /** Payments initiated by a user, newest first. */
    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Payment attempts for an order, newest first. */
    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
