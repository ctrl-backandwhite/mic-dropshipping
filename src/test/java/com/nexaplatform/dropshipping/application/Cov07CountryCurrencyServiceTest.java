package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CountryCurrencyService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La divisa que le toca a cada país.
 *
 * <p>Esta regla vivía dentro de {@code GeoController} y por eso solo la alcanzaba quien llegaba por HTTP.
 * Los correos se generan sin petición, así que caían al valor por defecto: un usuario dado de alta en
 * España recibía la campaña de novedades con los precios en DÓLARES — cifras que no puede comparar con lo
 * que verá al entrar en la tienda.
 *
 * <p>Lo que se protege aquí es que nunca lance. Este servicio se llama en mitad de un envío masivo: una
 * excepción por un país mal escrito en un perfil dejaría sin correo a toda la tanda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07CountryCurrencyServiceTest {

    @Mock
    private CurrencyRateService currencyRateService;

    @InjectMocks
    private CountryCurrencyService service;

    private static CurrencyRateEntity divisa(String code, boolean activa) {
        CurrencyRateEntity e = new CurrencyRateEntity();
        e.setCode(code);
        e.setActive(activa);
        return e;
    }

    @Test
    @DisplayName("un país de la zona euro con la divisa activa devuelve EUR")
    void paisConDivisaActiva() {
        when(currencyRateService.find("EUR")).thenReturn(Optional.of(divisa("EUR", true)));

        assertThat(service.forCountry("ES")).isEqualTo("EUR");
    }

    @Test
    @DisplayName("si la divisa del país no está activa en la plataforma, cae en dólares")
    void divisaNoActiva() {
        // El país existe y su divisa también (COP), pero si no está activa no se puede cobrar en ella:
        // mostrar un precio en una divisa que no se acepta sería peor que mostrarlo en dólares.
        when(currencyRateService.find("COP")).thenReturn(Optional.of(divisa("COP", false)));

        assertThat(service.forCountry("CO")).isEqualTo("USD");
    }

    @Test
    @DisplayName("un país que no existe cae en dólares sin lanzar")
    void paisInexistente() {
        // "ZZ" es sintácticamente válido pero no tiene divisa: Currency.getInstance lanza y aquí se
        // absorbe. Si no, una excepción tumbaría el envío de toda la tanda de correos.
        assertThat(service.forCountry("ZZ")).isEqualTo("USD");
    }

    @Test
    @DisplayName("nulo, vacío o en blanco caen en dólares")
    void sinPais() {
        assertThat(service.forCountry(null)).isEqualTo("USD");
        assertThat(service.forCountry("")).isEqualTo("USD");
        assertThat(service.forCountry("   ")).isEqualTo("USD");
    }

    @Test
    @DisplayName("tolera minúsculas y espacios alrededor")
    void toleraFormato() {
        when(currencyRateService.find("EUR")).thenReturn(Optional.of(divisa("EUR", true)));

        assertThat(service.forCountry(" es ")).isEqualTo("EUR");
        assertThat(service.forCountry("Es")).isEqualTo("EUR");
    }

    @Test
    @DisplayName("un valor que no es ISO-2 ni se consulta")
    void valorArbitrarioNiSeConsulta() {
        // Se corta ANTES de tocar la base: así un país inventado que llegue de una cabecera o de un perfil
        // mal rellenado no acaba lanzando consultas por divisas que no existen.
        assertThat(service.forCountry("ESPAÑA")).isEqualTo("USD");
        assertThat(service.forCountry("E")).isEqualTo("USD");
        assertThat(service.forCountry("<script>")).isEqualTo("USD");

        verify(currencyRateService, never()).find(anyString());
    }

    @Test
    @DisplayName("un país sin tasa registrada cae en dólares")
    void sinTasaRegistrada() {
        when(currencyRateService.find("JPY")).thenReturn(Optional.empty());

        assertThat(service.forCountry("JP")).isEqualTo("USD");
    }
}
