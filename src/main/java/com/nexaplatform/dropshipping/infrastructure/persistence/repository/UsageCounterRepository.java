package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UsageCounterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UsageCounterRepository extends JpaRepository<UsageCounterEntity, UUID> {
    Optional<UsageCounterEntity> findBySubscription_IdAndMetricAndPeriodStart(UUID subscriptionId, String metric, Instant periodStart);
}
