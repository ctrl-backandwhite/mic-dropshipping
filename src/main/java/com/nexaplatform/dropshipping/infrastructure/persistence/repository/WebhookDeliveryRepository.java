package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, UUID> {
    List<WebhookDeliveryEntity> findBySubscription_IdOrderByCreatedAtDesc(UUID subscriptionId);
    List<WebhookDeliveryEntity> findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(String status, Instant now);
}
