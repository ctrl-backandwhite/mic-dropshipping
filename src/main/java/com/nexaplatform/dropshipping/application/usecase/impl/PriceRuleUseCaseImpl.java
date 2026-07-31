package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.PriceRuleUpdateMapper;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.usecase.PriceRuleUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import com.nexaplatform.dropshipping.domain.repository.PriceRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY;

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

    // Every rule mutation flushes the margin cache AND the price caches (priceFor result, PDP, summary,
    // listing) so the change is reflected in prices immediately, across every currency.
    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
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
        return requireById(id);
    }

    /**
     * Carga la regla o lanza 404. Sin anotar a propósito: es la que usan las mutaciones de esta misma clase.
     * Llamar al método público desde dentro se salta el proxy de Spring, así que su {@code @Transactional} no
     * llegaría a aplicarse (java:S6809); la anotación queda solo en el punto de entrada.
     */
    private PriceRule requireById(UUID id) {
        PriceRule model = priceRuleRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Price rule not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public PriceRule update(PriceRule model, UUID id) {
        PriceRule existing = requireById(id);
        priceRuleUpdateMapper.updateFromModel(model, existing);
        PriceRule saved = priceRuleRepository.update(existing);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public PriceRule toggle(UUID id) {
        PriceRule existing = requireById(id);
        existing.setActive(!existing.isActive());
        PriceRule saved = priceRuleRepository.update(existing);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule {} -> active={}", id, saved.isActive());
        return saved;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public PriceRule setActive(UUID id, boolean active) {
        PriceRule existing = requireById(id);
        existing.setActive(active);
        PriceRule saved = priceRuleRepository.update(existing);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule {} -> active={}", id, saved.isActive());
        return saved;
    }

    @Override
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true)})
    public void delete(UUID id) {
        requireById(id);
        priceRuleRepository.delete(id);
        marginService.invalidateCache();
        log.info("::> [PRICING] Price rule deleted id={}", id);
    }
}
