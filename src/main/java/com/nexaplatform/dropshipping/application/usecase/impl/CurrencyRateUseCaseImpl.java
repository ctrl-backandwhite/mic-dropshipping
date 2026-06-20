package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.CurrencyRateUseCase;
import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.domain.model.CurrencySyncResult;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyLayerAdapter;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CurrencyRateEntityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Currency-rate use case for the CRUD/sync side. Holds all logic that used to
 * live in {@code CurrencyService}: listing, lookup, rate override and provider
 * sync. Operates on and returns the {@link CurrencyRate} model. Reads/mutations
 * are delegated to {@link CurrencyRateService} so its in-memory conversion cache
 * stays consistent (conversion + CRUD are intertwined through that cache, so the
 * conversion methods remain in {@code CurrencyRateService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyRateUseCaseImpl implements CurrencyRateUseCase {

    private final CurrencyRateService currencyRateService;
    private final CurrencyLayerAdapter currencyLayerAdapter;
    private final CurrencyRateEntityMapper currencyRateEntityMapper;

    @Override
    @Transactional(readOnly = true)
    public List<CurrencyRate> listActive() {
        return currencyRateEntityMapper.toDomainList(currencyRateService.listActive());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CurrencyRate> listAll() {
        return currencyRateEntityMapper.toDomainList(currencyRateService.listAll());
    }

    @Override
    @Transactional
    public CurrencyRate setActive(String code, boolean active) {
        return currencyRateEntityMapper.toDomain(currencyRateService.setActive(code, active));
    }

    @Override
    @Transactional
    public int bulkSetActive(List<String> codes, boolean active) {
        if (codes == null) {
            return 0;
        }
        int changed = 0;
        for (String code : codes) {
            try {
                currencyRateService.setActive(code, active);
                changed++;
            } catch (RuntimeException ignored) {
                /* una moneda desconocida no aborta el lote */ }
        }
        return changed;
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyRate one(String code) {
        return currencyRateEntityMapper.toDomain(currencyRateService.require(code));
    }

    @Override
    @Transactional
    public CurrencyRate updateRate(String code, BigDecimal rateVsUsd, Boolean active) {
        var updated = currencyRateService.overrideRate(code, rateVsUsd);
        if (active != null) {
            updated = currencyRateService.setActive(code, active);
        }
        return currencyRateEntityMapper.toDomain(updated);
    }

    @Override
    @Transactional
    public CurrencySyncResult sync() {
        Map<String, BigDecimal> rates = currencyLayerAdapter.fetchLive();
        if (rates.isEmpty()) {
            return CurrencySyncResult.builder().updated(0)
                    .message("No provider key set or provider returned empty payload.").build();
        }
        currencyRateService.applyBulkSync(rates);
        return CurrencySyncResult.builder().updated(rates.size()).message("Rates updated from provider.").build();
    }
}
