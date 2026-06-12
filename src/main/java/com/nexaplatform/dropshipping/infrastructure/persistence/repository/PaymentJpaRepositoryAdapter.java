package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code PaymentRepository} domain port. */
public interface PaymentJpaRepositoryAdapter extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByProviderAndProviderRef(String provider, String providerRef);

    List<PaymentEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<PaymentEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
