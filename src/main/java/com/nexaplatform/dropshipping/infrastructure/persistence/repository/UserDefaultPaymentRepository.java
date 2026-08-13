package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDefaultPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Puntero al método de pago predeterminado del usuario (tarjeta Stripe o PayPal). */
public interface UserDefaultPaymentRepository extends JpaRepository<UserDefaultPaymentEntity, UUID> {
}
