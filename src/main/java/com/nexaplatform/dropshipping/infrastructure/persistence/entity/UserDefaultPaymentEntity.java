package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Puntero al método de pago PREDETERMINADO del usuario, unificado sobre tarjetas (Stripe) y PayPal. En
 * tabla aparte para no modificar la entidad {@code users}. {@code ref} = pm_ de Stripe o 'paypal:&lt;id&gt;'.
 */
@Entity
@Table(name = "user_default_payment")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDefaultPaymentEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String ref;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
