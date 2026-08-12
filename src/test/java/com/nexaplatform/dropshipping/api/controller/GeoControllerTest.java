package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeoControllerTest {

    private final CurrencyRateService currency = mock(CurrencyRateService.class);
    private final GeoController controller = new GeoController(currency);

    @AfterEach
    void tearDown() {
        PricingCountryHolder.clear();
    }

    private static CurrencyRateEntity active(String code, boolean active) {
        CurrencyRateEntity e = new CurrencyRateEntity();
        e.setCode(code);
        e.setActive(active);
        return e;
    }

    @Test
    void sinPais_devuelveUsd() {
        PricingCountryHolder.clear();
        GeoController.GeoResponse r = controller.geo();
        assertThat(r.country()).isNull();
        assertThat(r.currency()).isEqualTo("USD");
    }

    @Test
    void paisUE_conDivisaActiva_devuelveEsaDivisa() {
        when(currency.find("EUR")).thenReturn(Optional.of(active("EUR", true)));
        PricingCountryHolder.set("ES");
        GeoController.GeoResponse r = controller.geo();
        assertThat(r.country()).isEqualTo("ES");
        assertThat(r.currency()).isEqualTo("EUR");
    }

    @Test
    void paisConDivisaNoSoportada_caeEnUsd() {
        // La divisa del país existe (COP) pero no está activa en la plataforma → USD.
        when(currency.find("COP")).thenReturn(Optional.of(active("COP", false)));
        PricingCountryHolder.set("CO");
        assertThat(controller.geo().currency()).isEqualTo("USD");
    }

    @Test
    void paisSinDivisaConocida_caeEnUsd() {
        // "XX"/"ZZ" no es un país ISO válido: Currency.getInstance lanza y se cae en USD sin romper.
        lenient().when(currency.find("USD")).thenReturn(Optional.of(active("USD", true)));
        PricingCountryHolder.set("ZZ");
        assertThat(controller.geo().currency()).isEqualTo("USD");
    }
}
