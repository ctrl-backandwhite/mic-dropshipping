package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.CurrencyRateUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.domain.model.CurrencySyncResult;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyLayerAdapter;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.CurrencyRateEntityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrencyRateUseCaseImplTest {

    @Mock
    CurrencyRateService currencyRateService;
    @Mock
    CurrencyLayerAdapter currencyLayerAdapter;
    @Mock
    CurrencyRateEntityMapper currencyRateEntityMapper;
    @InjectMocks
    CurrencyRateUseCaseImpl useCase;

    @Test
    void one_mapsRequiredEntityToModel() {
        CurrencyRateEntity entity = CurrencyRateEntity.builder().code("EUR").build();
        CurrencyRate model = CurrencyRate.builder().code("EUR").build();
        when(currencyRateService.require("EUR")).thenReturn(entity);
        when(currencyRateEntityMapper.toDomain(entity)).thenReturn(model);

        CurrencyRate result = useCase.one("EUR");

        assertThat(result.getCode()).isEqualTo("EUR");
        verify(currencyRateService).require("EUR");
    }

    @Test
    void updateRate_overridesThenTogglesActiveWhenProvided() {
        CurrencyRateEntity overridden = CurrencyRateEntity.builder().code("EUR").build();
        CurrencyRateEntity toggled = CurrencyRateEntity.builder().code("EUR").build();
        CurrencyRate model = CurrencyRate.builder().code("EUR").active(true).build();
        BigDecimal rate = new BigDecimal("1.08");
        when(currencyRateService.overrideRate("EUR", rate)).thenReturn(overridden);
        when(currencyRateService.setActive("EUR", true)).thenReturn(toggled);
        when(currencyRateEntityMapper.toDomain(toggled)).thenReturn(model);

        CurrencyRate result = useCase.updateRate("EUR", rate, true);

        assertThat(result.isActive()).isTrue();
        verify(currencyRateService).overrideRate("EUR", rate);
        verify(currencyRateService).setActive("EUR", true);
    }

    @Test
    void sync_returnsEmptyResultWhenProviderHasNoRates() {
        when(currencyLayerAdapter.fetchLive()).thenReturn(Map.of());

        CurrencySyncResult result = useCase.sync();

        assertThat(result.getUpdated()).isZero();
        assertThat(result.getMessage()).isEqualTo("No provider key set or provider returned empty payload.");
    }

    @Test
    void sync_appliesBulkAndReportsCount() {
        Map<String, BigDecimal> rates = Map.of("EUR", new BigDecimal("0.92"), "GBP", new BigDecimal("0.79"));
        when(currencyLayerAdapter.fetchLive()).thenReturn(rates);

        CurrencySyncResult result = useCase.sync();

        assertThat(result.getUpdated()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("Rates updated from provider.");
        verify(currencyRateService).applyBulkSync(rates);
    }
}
