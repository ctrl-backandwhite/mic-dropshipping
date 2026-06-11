package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA adapter backing the {@code SubscriptionPlanRepository} domain port. */
public interface SubscriptionPlanJpaRepositoryAdapter extends JpaRepository<SubscriptionPlanEntity, UUID> {
}
