package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El token de CJ: cuándo se pide, cuándo se reutiliza y cuándo se renueva.
 *
 * <p>CJ limita la autenticación a <b>una llamada por segundo</b> y devuelve el mismo token si se lo
 * vuelves a pedir dentro de 24 horas, así que pedirlo en cada petición es la forma segura de agotar la
 * cuota y quedarse sin transportista en mitad de un checkout. El token se guarda en la base de datos y
 * no en memoria: si solo viviera en el proceso, cada despliegue pediría uno nuevo.
 *
 * <p><b>Se renueva a los 10 días.</b> La documentación dice que el token dura 15, pero la respuesta
 * real del 18-ago-2026 daba 180. Renovar a los 10 deja margen con cualquiera de las dos cifras y hace
 * que un token revocado se detecte pronto, en vez de cuando un cliente esté pagando.
 */
class CjAuthServiceTest {

    private static final String API_KEY = "CJ0000000@api@clavesecretaquenodebesalir";
    private static final String TOKEN_VIEJO = "API@CJ0000000@CJ:token-anterior";
    private static final String TOKEN_NUEVO = "API@CJ0000000@CJ:token-recien-pedido";
    private static final String REFRESCO = "API@CJ0000000@CJ:refresco";
    private static final String OPEN_ID = "33689";

    /** Un instante fijo: sin reloj controlado no se pueden probar los diez días sin esperarlos. */
    private static final Instant AHORA = Instant.parse("2026-08-18T12:00:00Z");

    private final CarrierTokenRepository repositorio = mock(CarrierTokenRepository.class);
    private final CjTokenClient clienteDeCj = mock(CjTokenClient.class);

    private CjAuthService servicio;

    @BeforeEach
    void servicioConRelojFijo() {
        servicio = new CjAuthService(repositorio, clienteDeCj, Clock.fixed(AHORA, ZoneOffset.UTC));
        ReflectionTestUtils.setField(servicio, "apiKey", API_KEY);
        ReflectionTestUtils.setField(servicio, "diasHastaRenovar", 10);
    }

    /** Deja en la base un token obtenido hace la antigüedad indicada. */
    private void tokenGuardadoDeHace(Duration antiguedad, Instant caducidadDelRefresco) {
        CarrierTokenEntity guardado = CarrierTokenEntity.builder()
                .carrier("CJ").accessToken(TOKEN_VIEJO).refreshToken(REFRESCO).openId(OPEN_ID)
                .obtainedAt(AHORA.minus(antiguedad)).refreshExpiresAt(caducidadDelRefresco).build();
        when(repositorio.findByCarrier("CJ")).thenReturn(Optional.of(guardado));
    }

    private void cjDevuelveTokenNuevo() {
        CjToken nuevo = new CjToken(TOKEN_NUEVO, REFRESCO, OPEN_ID, AHORA.plus(Duration.ofDays(180)));
        when(clienteDeCj.obtenerConApiKey(anyString())).thenReturn(nuevo);
        when(clienteDeCj.refrescar(anyString())).thenReturn(nuevo);
    }

    // ------------------------------------------------------------------ la primera vez

    @Test
    @DisplayName("sin token guardado se autentica con la clave y lo guarda")
    void pideElTokenLaPrimeraVez() {
        when(repositorio.findByCarrier("CJ")).thenReturn(Optional.empty());
        cjDevuelveTokenNuevo();

        String token = servicio.tokenVigente();

        assertThat(token).isEqualTo(TOKEN_NUEVO);
        verify(clienteDeCj).obtenerConApiKey(API_KEY);
        ArgumentCaptor<CarrierTokenEntity> guardado = ArgumentCaptor.forClass(CarrierTokenEntity.class);
        verify(repositorio).save(guardado.capture());
        assertThat(guardado.getValue().getAccessToken())
                .as("si no se guarda, el siguiente arranque vuelve a pedirlo y CJ limita a 1 por segundo")
                .isEqualTo(TOKEN_NUEVO);
        assertThat(guardado.getValue().getOpenId())
                .as("el openId es el secreto con el que se firma el webhook: sin él no se puede verificar")
                .isEqualTo(OPEN_ID);
    }

    // ------------------------------------------------------------------ el paso del tiempo

    @ParameterizedTest(name = "token de hace {0} días y {1} horas → ¿pide uno nuevo? {2}")
    @CsvSource({
            "0,  1, false",
            "9, 23, false",
            // El borde exacto: a los diez días justos ya toca renovar.
            "10, 0, true",
            "10, 1, true",
            "45, 0, true",
    })
    @DisplayName("el token se reutiliza hasta los diez días y se renueva a partir de ahí")
    void renuevaALosDiezDias(int dias, int horas, boolean deberiaRenovar) {
        tokenGuardadoDeHace(Duration.ofDays(dias).plusHours(horas), AHORA.plus(Duration.ofDays(90)));
        cjDevuelveTokenNuevo();

        String token = servicio.tokenVigente();

        if (deberiaRenovar) {
            verify(clienteDeCj).refrescar(REFRESCO);
            assertThat(token).isEqualTo(TOKEN_NUEVO);
        } else {
            verify(clienteDeCj, never()).refrescar(anyString());
            verify(clienteDeCj, never()).obtenerConApiKey(anyString());
            assertThat(token).isEqualTo(TOKEN_VIEJO);
        }
    }

    @Test
    @DisplayName("si el refresco ya caducó se vuelve a autenticar con la clave")
    void siElRefrescoCaducaVuelveAAutenticarse() {
        tokenGuardadoDeHace(Duration.ofDays(11), AHORA.minus(Duration.ofDays(1)));
        cjDevuelveTokenNuevo();

        servicio.tokenVigente();

        verify(clienteDeCj).obtenerConApiKey(API_KEY);
        verify(clienteDeCj, never()).refrescar(anyString());
    }

    @Test
    @DisplayName("si el refresco falla se cae a la clave en vez de dejar la tienda sin transportista")
    void siElRefrescoFallaSeUsaLaClave() {
        tokenGuardadoDeHace(Duration.ofDays(11), AHORA.plus(Duration.ofDays(90)));
        when(clienteDeCj.refrescar(anyString())).thenThrow(new IllegalStateException("refresco rechazado"));
        when(clienteDeCj.obtenerConApiKey(anyString()))
                .thenReturn(new CjToken(TOKEN_NUEVO, REFRESCO, OPEN_ID, AHORA.plus(Duration.ofDays(180))));

        assertThat(servicio.tokenVigente()).isEqualTo(TOKEN_NUEVO);
        verify(clienteDeCj).obtenerConApiKey(API_KEY);
    }

    // ------------------------------------------------------------------ el openId

    @Test
    @DisplayName("el openId sale del token guardado, sin volver a llamar a CJ")
    void elOpenIdNoCuestaUnaLlamada() {
        tokenGuardadoDeHace(Duration.ofDays(1), AHORA.plus(Duration.ofDays(90)));

        assertThat(servicio.openId()).isEqualTo(OPEN_ID);
        verify(clienteDeCj, never()).obtenerConApiKey(anyString());
    }

    // ------------------------------------------------------------------ la clave no se filtra

    @Test
    @DisplayName("cuando la autenticación falla, el error NO lleva la clave dentro")
    void noFiltraLaClaveEnElError() {
        when(repositorio.findByCarrier("CJ")).thenReturn(Optional.empty());
        when(clienteDeCj.obtenerConApiKey(anyString()))
                .thenThrow(new IllegalStateException("credenciales rechazadas"));

        assertThatThrownBy(() -> servicio.tokenVigente())
                .as("los mensajes de error acaban en el registro y en las alertas: la clave no puede ir ahí")
                .hasMessageNotContaining(API_KEY)
                .hasMessageNotContaining("clavesecretaquenodebesalir");
    }

    @Test
    @DisplayName("sin clave configurada se avisa claro, en vez de llamar a CJ con la cadena vacía")
    void sinClaveConfiguradaNoSeLlamaACj() {
        ReflectionTestUtils.setField(servicio, "apiKey", "");
        when(repositorio.findByCarrier("CJ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.tokenVigente())
                .hasMessageContaining("CJ_API_KEY");
        verify(clienteDeCj, never()).obtenerConApiKey(any());
    }
}
