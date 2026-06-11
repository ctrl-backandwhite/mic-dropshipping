package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code WebhookSubscriptionRepository} domain port. */
public interface WebhookSubscriptionJpaRepositoryAdapter extends JpaRepository<WebhookSubscriptionEntity, UUID> {

    List<WebhookSubscriptionEntity> findByActiveTrue();

    List<WebhookSubscriptionEntity> findByUser_Id(UUID userId);
}
