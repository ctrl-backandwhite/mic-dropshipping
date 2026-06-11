package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.SubscriptionPlanUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Subscription plan use-case implementation. Works on the domain model and
 * delegates persistence to the {@link SubscriptionPlanRepository} port.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionPlanUseCaseImpl implements SubscriptionPlanUseCase {

    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final SubscriptionPlanUpdateMapper subscriptionPlanUpdateMapper;

    @Override
    @Transactional
    public SubscriptionPlan save(SubscriptionPlan model) {
        log.debug("::> [BILLING] Creating plan code={}", model.getCode());
        return subscriptionPlanRepository.save(model);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlan> findAll() {
        return subscriptionPlanRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionPlan getById(UUID id) {
        SubscriptionPlan model = subscriptionPlanRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Subscription plan not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    public SubscriptionPlan update(SubscriptionPlan model, UUID id) {
        SubscriptionPlan existing = getById(id);
        subscriptionPlanUpdateMapper.updateFromModel(model, existing);
        log.debug("::> [BILLING] Updating plan id={}", id);
        return subscriptionPlanRepository.update(existing);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        log.debug("::> [BILLING] Deleting plan id={}", id);
        subscriptionPlanRepository.delete(id);
    }
}
