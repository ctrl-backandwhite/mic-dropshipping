package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
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
 * Un canje de cupón (tabla {@code promotion_redemption}).
 *
 * <p>Sostiene el tope POR PERSONA, que no se puede deducir del contador global, y deja el rastro de
 * quién usó qué — necesario para responder «¿este cliente ya lo canjeó?» sin rastrear pedidos.
 */
@Entity
@Table(name = "promotion_redemption")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionRedemptionEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "promotion_id", nullable = false, columnDefinition = "uuid")
    private UUID promotionId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "order_id", columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;
}
