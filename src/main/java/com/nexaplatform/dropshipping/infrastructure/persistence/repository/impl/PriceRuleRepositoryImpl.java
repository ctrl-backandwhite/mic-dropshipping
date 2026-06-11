package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.PriceRule;
import com.nexaplatform.dropshipping.domain.repository.PriceRuleRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.PriceRuleEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PriceRuleJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link PriceRuleRepository} domain port
 * on top of Spring Data JPA. {@code findAll} preserves the legacy contract of the
 * admin list (active rules ordered by position).
 */
@Repository
@RequiredArgsConstructor
public class PriceRuleRepositoryImpl implements PriceRuleRepository {

    private final PriceRuleEntityMapper priceRuleEntityMapper;
    private final PriceRuleJpaRepositoryAdapter priceRuleJpaRepositoryAdapter;

    @Override
    public PriceRule save(PriceRule model) {
        var entity = priceRuleJpaRepositoryAdapter.save(priceRuleEntityMapper.toEntity(model));
        return priceRuleEntityMapper.toDomain(entity);
    }

    @Override
    public List<PriceRule> findAll() {
        return priceRuleEntityMapper.toDomainList(priceRuleJpaRepositoryAdapter.findByActiveTrueOrderByPositionAsc());
    }

    @Override
    public PriceRule update(PriceRule model) {
        return this.save(model);
    }

    @Override
    public PriceRule getById(UUID id) {
        return priceRuleJpaRepositoryAdapter.findById(id)
                .map(priceRuleEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        priceRuleJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return priceRuleJpaRepositoryAdapter.existsById(id);
    }
}
