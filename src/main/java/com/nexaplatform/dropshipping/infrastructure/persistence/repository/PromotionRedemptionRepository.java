package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionRedemptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Canjes de cupones. */
public interface PromotionRedemptionRepository extends JpaRepository<PromotionRedemptionEntity, UUID> {

    /** Cuántas veces ha canjeado ESTA persona ESTE cupón: es el tope por usuario. */
    long countByPromotionIdAndUserId(UUID promotionId, UUID userId);

    boolean existsByPromotionIdAndOrderId(UUID promotionId, UUID orderId);
}
