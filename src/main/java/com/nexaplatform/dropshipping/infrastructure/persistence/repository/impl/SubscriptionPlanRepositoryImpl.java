package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SubscriptionPlanEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SubscriptionPlanRepository}
 * domain port on top of Spring Data JPA, mapping domain models to/from entities.
 */
@Repository
@RequiredArgsConstructor
public class SubscriptionPlanRepositoryImpl implements SubscriptionPlanRepository {

    private final SubscriptionPlanEntityMapper subscriptionPlanEntityMapper;
    private final SubscriptionPlanJpaRepositoryAdapter subscriptionPlanJpaRepositoryAdapter;

    @Override
    public SubscriptionPlan save(SubscriptionPlan model) {
        SubscriptionPlanEntity entity = subscriptionPlanJpaRepositoryAdapter
                .save(subscriptionPlanEntityMapper.toEntity(model));
        return subscriptionPlanEntityMapper.toDomain(entity);
    }

    @Override
    public List<SubscriptionPlan> findAll() {
        return subscriptionPlanEntityMapper.toDomainList(subscriptionPlanJpaRepositoryAdapter.findAll());
    }

    @Override
    public SubscriptionPlan update(SubscriptionPlan model) {
        return this.save(model);
    }

    @Override
    public SubscriptionPlan getById(UUID id) {
        return subscriptionPlanJpaRepositoryAdapter.findById(id).map(subscriptionPlanEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        subscriptionPlanJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return subscriptionPlanJpaRepositoryAdapter.existsById(id);
    }
}
