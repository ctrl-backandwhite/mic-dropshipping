package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

class GeoControllerTest {

    private final CountryCurrencyService countryCurrency = mock(CountryCurrencyService.class);
    private final GeoController controller = new GeoController(countryCurrency);

    @BeforeEach
    void porDefectoUsd() {
        // El servicio real nunca devuelve null: ante cualquier país que no pueda resolver, devuelve USD.
        // El mock tiene que comportarse igual, o los casos de país ausente o inválido comprueban un null
        // que en producción no puede darse.
        lenient().when(countryCurrency.forCountry(nullable(String.class))).thenReturn("USD");
    }

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
        when(countryCurrency.forCountry("ES")).thenReturn("EUR");
        PricingCountryHolder.set("ES");
        GeoController.GeoResponse r = controller.geo();
        assertThat(r.country()).isEqualTo("ES");
        assertThat(r.currency()).isEqualTo("EUR");
    }

    @Test
    void paisConDivisaNoSoportada_caeEnUsd() {
        // Quien decide que COP no vale es el servicio; aquí solo se comprueba que el controlador
        // devuelve lo que aquel diga.
        when(countryCurrency.forCountry("CO")).thenReturn("USD");
        PricingCountryHolder.set("CO");
        assertThat(controller.geo().currency()).isEqualTo("USD");
    }

    @Test
    void paisSinDivisaConocida_caeEnUsd() {
        // "ZZ" es sintácticamente ISO-2, así que el controlador lo pasa; el servicio devuelve USD.
        when(countryCurrency.forCountry("ZZ")).thenReturn("USD");
        PricingCountryHolder.set("ZZ");
        assertThat(controller.geo().currency()).isEqualTo("USD");
    }

    @Test
    void cabeceraXCountryArbitraria_noSeRefleja_yCaeEnUsd() {
        // Saneo: una X-Country inyectada (larga / con símbolos) NO debe reflejarse; country=null, moneda USD.
        PricingCountryHolder.set("<script>alert(1)</script>");
        GeoController.GeoResponse r1 = controller.geo();
        assertThat(r1.country()).isNull();
        assertThat(r1.currency()).isEqualTo("USD");

        PricingCountryHolder.set("ESPANA");
        assertThat(controller.geo().country()).isNull();

        PricingCountryHolder.set("E1");
        assertThat(controller.geo().country()).isNull();
    }
}
