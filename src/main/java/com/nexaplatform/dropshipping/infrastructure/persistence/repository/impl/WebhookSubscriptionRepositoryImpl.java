package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import com.nexaplatform.dropshipping.domain.repository.WebhookSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WebhookSubscriptionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link WebhookSubscriptionRepository}
 * domain port on top of Spring Data JPA. {@code findAll} preserves the legacy
 * admin-list contract: newest first (createdAt descending, nulls last).
 */
@Repository
@RequiredArgsConstructor
public class WebhookSubscriptionRepositoryImpl implements WebhookSubscriptionRepository {

    private final WebhookSubscriptionEntityMapper webhookSubscriptionEntityMapper;
    private final WebhookSubscriptionJpaRepositoryAdapter webhookSubscriptionJpaRepositoryAdapter;

    @Override
    public WebhookSubscription save(WebhookSubscription model) {
        WebhookSubscriptionEntity entity = webhookSubscriptionJpaRepositoryAdapter
                .save(webhookSubscriptionEntityMapper.toEntity(model));
        return webhookSubscriptionEntityMapper.toDomain(entity);
    }

    @Override
    public List<WebhookSubscription> findAll() {
        List<WebhookSubscriptionEntity> sorted = webhookSubscriptionJpaRepositoryAdapter.findAll().stream()
                .sorted(Comparator.comparing(WebhookSubscriptionEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return webhookSubscriptionEntityMapper.toDomainList(sorted);
    }

    @Override
    public WebhookSubscription update(WebhookSubscription model) {
        return this.save(model);
    }

    @Override
    public WebhookSubscription getById(UUID id) {
        return webhookSubscriptionJpaRepositoryAdapter.findById(id).map(webhookSubscriptionEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        webhookSubscriptionJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return webhookSubscriptionJpaRepositoryAdapter.existsById(id);
    }
}
