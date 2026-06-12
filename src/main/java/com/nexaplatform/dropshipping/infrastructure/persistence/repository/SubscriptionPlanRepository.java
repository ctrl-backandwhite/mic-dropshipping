package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlanEntity, UUID> {
    List<SubscriptionPlanEntity> findByActiveTrueOrderByPositionAsc();

    Optional<SubscriptionPlanEntity> findByCode(String code);
}
