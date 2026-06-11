package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.PriceRuleUpdateMapper;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.usecase.PriceRuleUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import com.nexaplatform.dropshipping.domain.repository.PriceRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pricing-rule use case. Operates on the {@link PriceRule} model and delegates
 * persistence to the domain port. Flushes the {@link MarginService} cache on
 * every mutation so margin resolution stays consistent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceRuleUseCaseImpl implements PriceRuleUseCase {

    private final PriceRuleRepository priceRuleRepository;
    private final PriceRuleUpdateMapper priceRuleUpdateMapper;
    private final MarginService marginService;

    @Override
    @Transactional
    public PriceRule save(PriceRule model) {
        PriceRule saved = priceRuleRepository.save(model);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceRule> findAll() {
        return priceRuleRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public PriceRule getById(UUID id) {
        PriceRule model = priceRuleRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Price rule not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    public PriceRule update(PriceRule model, UUID id) {
        PriceRule existing = getById(id);
        priceRuleUpdateMapper.updateFromModel(model, existing);
        PriceRule saved = priceRuleRepository.update(existing);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        priceRuleRepository.delete(id);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule deleted id={}", id);
    }
}
