package com.nexaplatform.dropshipping.infrastructure.integration.currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sincronización diaria de tipos de cambio con CurrencyLayer.
 *
 * <p>La regla de oro: las tasas guardadas solo se sustituyen si la respuesta es buena. Sin clave de API,
 * con un cuerpo sin éxito o con la API caída, el job NO toca nada y se conservan las tasas vigentes —
 * machacarlas con un mapa vacío dejaría todos los precios convertidos a cero.
 */
class Cov09CurrencyLayerAdapterTest {

    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersUriSpec uriSpec;
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec headersSpec;
    private WebClient.ResponseSpec responseSpec;
    private WebClient.Builder builder;
    private CurrencyRateService currencyRateService;

    private CurrencyLayerAdapter subject;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void buildSubject() {
        builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);
        currencyRateService = mock(CurrencyRateService.class);

        when(builder.build()).thenReturn(webClient);
        when(webClient.get()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        subject = new CurrencyLayerAdapter(builder, currencyRateService);
        ReflectionTestUtils.setField(subject, "apiUrl", "https://api.currencylayer.com/live");
        ReflectionTestUtils.setField(subject, "accessKey", "clave-de-prueba");
    }

    /** Cuerpo que devolverá la API en la siguiente llamada. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void responde(Map<String, Object> body) {
        when(responseSpec.bodyToMono(Map.class)).thenReturn((Mono) Mono.just(body));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void respondeVacio() {
        when(responseSpec.bodyToMono(Map.class)).thenReturn((Mono) Mono.empty());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void respondeConFallo() {
        when(responseSpec.bodyToMono(Map.class)).thenReturn((Mono) Mono.error(new IllegalStateException("API caída")));
    }

    private static Map<String, Object> cuerpo(boolean success, Map<String, Object> quotes) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", success);
        if (quotes != null) {
            body.put("quotes", quotes);
        }
        return body;
    }

    // ---------------------------------------------------------------- sin clave configurada

    @Test
    void sinClaveDeApiElJobNoLlamaANadieYConservaLasTasasSembradas() {
        ReflectionTestUtils.setField(subject, "accessKey", "  ");

        subject.scheduledSync();

        verifyNoInteractions(builder);
        verify(currencyRateService, never()).applyBulkSync(any());
    }

    @Test
    void sinClaveDeApiLaConsultaDirectaDevuelveVacioSinSalirARed() {
        ReflectionTestUtils.setField(subject, "accessKey", null);

        assertThat(subject.fetchLive()).isEmpty();

        verifyNoInteractions(builder);
    }

    // ---------------------------------------------------------------- lectura de cotizaciones

    @Test
    void soloSeQuedanLasCotizacionesContraElDolar() {
        // La cuenta gratuita cotiza siempre contra USD (USDEUR=0,92) y eso es justo lo que guardamos como
        // rate_vs_usd; cualquier par cruzado (EURGBP) no encaja en ese modelo y se descarta.
        Map<String, Object> quotes = new LinkedHashMap<>();
        quotes.put("USDEUR", 0.92);
        quotes.put("USDGBP", 0.79);
        quotes.put("EURGBP", 0.86);
        quotes.put("USDX", 1.0);
        responde(cuerpo(true, quotes));

        Map<String, BigDecimal> out = subject.fetchLive();

        assertThat(out).containsOnlyKeys("EUR", "GBP");
        assertThat(out.get("EUR")).isEqualByComparingTo("0.92");
    }

    @Test
    void lasClavesEnMinusculasSeNormalizanAMayusculas() {
        responde(cuerpo(true, Map.of("usdcop", 4123.45)));

        assertThat(subject.fetchLive()).containsOnlyKeys("COP");
    }

    @Test
    void unaRespuestaSinExitoNoDevuelveTasas() {
        // Aceptar un cuerpo con success:false traería cotizaciones a medias o de error.
        responde(cuerpo(false, Map.of("USDEUR", 0.92)));

        assertThat(subject.fetchLive()).isEmpty();
    }

    @Test
    void unaRespuestaSinCuerpoNoDevuelveTasas() {
        respondeVacio();

        assertThat(subject.fetchLive()).isEmpty();
    }

    @Test
    void unaRespuestaCorrectaPeroSinCotizacionesNoDevuelveTasas() {
        responde(cuerpo(true, null));

        assertThat(subject.fetchLive()).isEmpty();
    }

    // ---------------------------------------------------------------- job programado

    @Test
    void elJobAplicaLasTasasObtenidas() {
        responde(cuerpo(true, Map.of("USDEUR", 0.92)));

        subject.scheduledSync();

        verify(currencyRateService).applyBulkSync(Map.of("EUR", new BigDecimal("0.92")));
    }

    @Test
    void siLaApiFallaElJobNoRevientaYNoAplicaNada() {
        // Es un job programado: una excepción aquí quedaría solo en el log del scheduler, y peor aún,
        // aplicar un mapa a medias dejaría precios convertidos con tasas inventadas.
        respondeConFallo();

        assertThatCode(() -> subject.scheduledSync()).doesNotThrowAnyException();

        verify(currencyRateService, never()).applyBulkSync(any());
    }
}
