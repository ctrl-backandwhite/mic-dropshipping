package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;

import java.util.UUID;

/**
 * Domain repository port for {@link WebhookSubscription}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface of the same simple name in
 * {@code infrastructure.persistence.repository} (intentional).
 */
public interface WebhookSubscriptionRepository extends BaseRepository<WebhookSubscription, WebhookSubscription, UUID> {
}
