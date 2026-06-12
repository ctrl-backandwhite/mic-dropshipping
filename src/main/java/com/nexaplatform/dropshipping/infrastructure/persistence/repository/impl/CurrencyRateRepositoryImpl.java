package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.domain.repository.CurrencyRateRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CurrencyRateEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CurrencyRateJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link CurrencyRateRepository} domain
 * port on top of Spring Data JPA. {@code findActive} preserves the legacy
 * contract (active rates ordered by code); {@code findByCode} is case-insensitive.
 */
@Repository
@RequiredArgsConstructor
public class CurrencyRateRepositoryImpl implements CurrencyRateRepository {

    private final CurrencyRateEntityMapper currencyRateEntityMapper;
    private final CurrencyRateJpaRepositoryAdapter currencyRateJpaRepositoryAdapter;

    @Override
    public CurrencyRate save(CurrencyRate model) {
        var entity = currencyRateJpaRepositoryAdapter.save(currencyRateEntityMapper.toEntity(model));
        return currencyRateEntityMapper.toDomain(entity);
    }

    @Override
    public List<CurrencyRate> findAll() {
        return currencyRateEntityMapper.toDomainList(currencyRateJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<CurrencyRate> findActive() {
        return currencyRateEntityMapper.toDomainList(currencyRateJpaRepositoryAdapter.findByActiveTrueOrderByCodeAsc());
    }

    @Override
    public Optional<CurrencyRate> findByCode(String code) {
        return currencyRateJpaRepositoryAdapter.findByCodeIgnoreCase(code).map(currencyRateEntityMapper::toDomain);
    }

    @Override
    public CurrencyRate update(CurrencyRate model) {
        return this.save(model);
    }

    @Override
    public CurrencyRate getById(UUID id) {
        return currencyRateJpaRepositoryAdapter.findById(id).map(currencyRateEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        currencyRateJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return currencyRateJpaRepositoryAdapter.existsById(id);
    }
}
