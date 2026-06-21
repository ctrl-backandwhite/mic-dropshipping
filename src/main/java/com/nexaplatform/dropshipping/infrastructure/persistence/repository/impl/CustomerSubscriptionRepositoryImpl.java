package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CustomerSubscriptionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link CustomerSubscriptionRepository}
 * domain port on top of Spring Data JPA. Owns the persistence-only concern the
 * domain model abstracts away: resolving the managed {@code user} and {@code plan}
 * relations from the flattened {@code userId}/{@code planId} on save/update. The
 * legacy Spring Data {@code UserRepository}/{@code SubscriptionPlanRepository} are
 * reused as read-only collaborators to fetch those managed entities.
 */
@Repository
@RequiredArgsConstructor
public class CustomerSubscriptionRepositoryImpl implements CustomerSubscriptionRepository {

    private final CustomerSubscriptionEntityMapper customerSubscriptionEntityMapper;
    private final CustomerSubscriptionJpaRepositoryAdapter customerSubscriptionJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;

    @Override
    public CustomerSubscription save(CustomerSubscription model) {
        CustomerSubscriptionEntity entity = resolveEntity(model);
        applyModel(entity, model);
        CustomerSubscriptionEntity saved = customerSubscriptionJpaRepositoryAdapter.save(entity);
        return customerSubscriptionEntityMapper.toDomain(saved);
    }

    @Override
    public List<CustomerSubscription> findAll() {
        return customerSubscriptionEntityMapper.toDomainList(customerSubscriptionJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<CustomerSubscription> findByUserId(UUID userId) {
        return customerSubscriptionEntityMapper
                .toDomainList(customerSubscriptionJpaRepositoryAdapter.findByUserId(userId));
    }

    @Override
    public Optional<CustomerSubscription> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return customerSubscriptionJpaRepositoryAdapter.findByStripeSubscriptionId(stripeSubscriptionId)
                .map(customerSubscriptionEntityMapper::toDomain);
    }

    @Override
    public CustomerSubscription update(CustomerSubscription model) {
        return this.save(model);
    }

    @Override
    public CustomerSubscription getById(UUID id) {
        return customerSubscriptionJpaRepositoryAdapter.findById(id).map(customerSubscriptionEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        customerSubscriptionJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return customerSubscriptionJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private CustomerSubscriptionEntity resolveEntity(CustomerSubscription model) {
        if (model.getId() != null) {
            return customerSubscriptionJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Subscription"));
        }
        return new CustomerSubscriptionEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the managed relations. */
    private void applyModel(CustomerSubscriptionEntity entity, CustomerSubscription model) {
        // Campos escalares vía MapStruct; las relaciones gestionadas (user, plan) se resuelven
        // abajo porque requieren lookups de repositorio.
        customerSubscriptionEntityMapper.updateEntity(entity, model);
        entity.setUser(resolveUser(model.getUserId()));
        entity.setPlan(resolvePlan(model.getPlanId()));
    }

    /** Resolves the owning user from its id, failing if it does not exist. */
    private UserEntity resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Resolves the related plan from its id, failing if it does not exist. */
    private SubscriptionPlanEntity resolvePlan(UUID planId) {
        if (planId == null) {
            return null;
        }
        return subscriptionPlanRepository.findById(planId).orElseThrow(() -> new NotFoundException("Plan not found"));
    }
}
