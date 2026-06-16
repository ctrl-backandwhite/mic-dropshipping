package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlanEntity, UUID> {
    List<SubscriptionPlanEntity> findByActiveTrueOrderByPositionAsc();

    /**
     * Active plans WITH their features fetched in one query. Avoids the LazyInitializationException when
     * the controller maps {@code plan.features} (the DtoMapper runs after the use-case transaction closes).
     */
    @Query("SELECT DISTINCT p FROM SubscriptionPlanEntity p LEFT JOIN FETCH p.features "
            + "WHERE p.active = true ORDER BY p.position ASC")
    List<SubscriptionPlanEntity> findActiveWithFeatures();

    Optional<SubscriptionPlanEntity> findByCode(String code);
}
