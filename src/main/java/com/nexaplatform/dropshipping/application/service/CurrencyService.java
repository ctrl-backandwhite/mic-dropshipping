package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.UpdateRateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncResultDtoOut;
import com.nexaplatform.dropshipping.api.mapper.CurrencyDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyLayerAdapter;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Use-case service for the Currency resource. Holds all logic previously living
 * in {@code CurrencyController}: listing, lookup, rate override and provider sync.
 */
@Service
@RequiredArgsConstructor
public class CurrencyService {

    private final CurrencyRateService currencyService;
    private final CurrencyLayerAdapter currencyLayerAdapter;
    private final CurrencyDtoMapper mapper;

    /** List all active currency rates. */
    public List<CurrencyDtoOut> listActive() {
        return mapper.toDtoOutList(currencyService.listActive());
    }

    /** Get a single currency by its code. */
    public CurrencyDtoOut one(String code) {
        return mapper.toDtoOut(currencyService.require(code));
    }

    /** Override a currency rate and optionally toggle its active flag. */
    public CurrencyDtoOut updateRate(String code, UpdateRateDtoIn req) {
        CurrencyRateEntity updated = currencyService.overrideRate(code, req.getRateVsUsd());
        if (req.getActive() != null) {
            updated = currencyService.setActive(code, req.getActive());
        }
        return mapper.toDtoOut(updated);
    }

    /** Sync currency rates from the external provider. */
    public CurrencySyncResultDtoOut sync() {
        Map<String, BigDecimal> rates = currencyLayerAdapter.fetchLive();
        if (rates.isEmpty()) {
            return CurrencySyncResultDtoOut.builder()
                    .updated(0)
                    .message("No provider key set or provider returned empty payload.")
                    .build();
        }
        currencyService.applyBulkSync(rates);
        return CurrencySyncResultDtoOut.builder()
                .updated(rates.size())
                .message("Rates updated from provider.")
                .build();
    }
}
