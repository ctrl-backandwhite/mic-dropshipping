package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Categorías y productos a los que alcanza cada promoción. */
public interface PromotionTargetRepository extends JpaRepository<PromotionTargetEntity, UUID> {

    List<PromotionTargetEntity> findByPromotionId(UUID promotionId);

    /** Los destinos de varias promociones de golpe, para no consultar una vez por promoción. */
    List<PromotionTargetEntity> findByPromotionIdIn(Collection<UUID> promotionIds);

    void deleteByPromotionId(UUID promotionId);
}
