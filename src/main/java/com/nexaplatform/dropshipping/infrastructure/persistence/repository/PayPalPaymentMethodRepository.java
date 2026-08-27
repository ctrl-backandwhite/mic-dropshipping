package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PayPalPaymentMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Cuentas PayPal guardadas por el usuario (método de pago para el checkout). */
public interface PayPalPaymentMethodRepository extends JpaRepository<PayPalPaymentMethodEntity, UUID> {

    List<PayPalPaymentMethodEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);
}
