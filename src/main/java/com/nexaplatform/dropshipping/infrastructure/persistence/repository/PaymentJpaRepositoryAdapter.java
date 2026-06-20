package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code PaymentRepository} domain port. */
public interface PaymentJpaRepositoryAdapter extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByProviderAndProviderRef(String provider, String providerRef);

    List<PaymentEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<PaymentEntity> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /** Proyección de la moneda de liquidación (sin cargar el usuario lazy) — para la factura. */
    @Query("SELECT p.settlementCurrency FROM PaymentEntity p WHERE p.orderId = :orderId ORDER BY p.createdAt DESC")
    List<String> findSettlementCurrenciesByOrderId(@Param("orderId") UUID orderId);
}
