package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjAuthService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjToken;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjTokenClient;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CarrierTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El token de CJ sobrevive en la base de datos, entero.
 *
 * <p>Lo que aquí se comprueba no lo puede ver una prueba unitaria, porque depende del esquema real:
 *
 * <ul>
 *   <li><b>Que cabe.</b> El token que devolvió CJ el 18-ago-2026 ocupa <b>566 caracteres</b>. La columna
 *       es {@code TEXT} por eso; con el {@code VARCHAR(255)} que sale por defecto, la fila se rechazaría
 *       en producción y el fallo aparecería a los diez días, al renovar, no al desplegar.</li>
 *   <li><b>Que solo hay uno.</b> Un índice único por transportista impide acabar con dos filas y media
 *       plataforma usando un token y media otro.</li>
 *   <li><b>Que se reutiliza.</b> Guardarlo no sirve de nada si la siguiente llamada vuelve a pedirlo:
 *       CJ limita la autenticación a una por segundo.</li>
 * </ul>
 *
 * <p>El que se sustituye es el cliente HTTP, no el servicio: aquí se certifica nuestra persistencia, no
 * la API de CJ. Llamar a CJ de verdad desde una prueba la haría depender de la red y del límite de
 * peticiones de la cuenta.
 */
// Clave INVENTADA, y a propósito: la de verdad vive en el entorno y no puede acabar en el repositorio.
// Aquí solo hace falta que exista alguna, porque el servicio se niega a llamar a CJ sin ella.
@TestPropertySource(properties = "nexadrop.cj.api-key=CJ0000000@api@clave-de-laboratorio")
@DisplayName("Token de CJ · se guarda entero, uno solo, y se reutiliza")
class CjTokenPersistidoIT extends BaseIntegration {

    /** Longitud real medida del token de CJ. */
    private static final int LARGO_REAL = 566;
    private static final String TOKEN_LARGO = "API@CJ5236391@CJ:" + "e".repeat(LARGO_REAL - 17);
    private static final String REFRESCO = "API@CJ5236391@CJ:refresco";
    private static final String OPEN_ID = "33689";

    @MockitoBean
    private CjTokenClient clienteDeCj;

    @Autowired
    private CjAuthService servicio;
    @Autowired
    private CarrierTokenRepository repositorio;

    @Test
    @DisplayName("un token de 566 caracteres se guarda y se relee sin recortarse")
    void elTokenCabeEntero() {
        when(clienteDeCj.obtenerConApiKey(anyString())).thenReturn(
                new CjToken(TOKEN_LARGO, REFRESCO, OPEN_ID, Instant.now().plus(Duration.ofDays(90))));

        String token = servicio.tokenVigente();

        assertThat(token).hasSize(LARGO_REAL);
        assertThat(repositorio.findByCarrier("CJ")).isPresent().get()
                .satisfies(fila -> {
                    assertThat(fila.getAccessToken())
                            .as("si la columna recortara, la petición siguiente iría con un token inválido")
                            .isEqualTo(TOKEN_LARGO);
                    assertThat(fila.getOpenId()).isEqualTo(OPEN_ID);
                    assertThat(fila.getObtainedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("pedirlo dos veces seguidas no gasta dos autenticaciones")
    void seReutilizaElYaGuardado() {
        when(clienteDeCj.obtenerConApiKey(anyString())).thenReturn(
                new CjToken(TOKEN_LARGO, REFRESCO, OPEN_ID, Instant.now().plus(Duration.ofDays(90))));

        servicio.tokenVigente();
        servicio.tokenVigente();
        servicio.tokenVigente();

        verify(clienteDeCj, times(1))
                .obtenerConApiKey(anyString());
        assertThat(repositorio.count())
                .as("una fila por transportista: dos dejarían media plataforma con un token y media con otro")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el openId queda disponible para verificar la firma del webhook")
    void elOpenIdQuedaAMano() {
        when(clienteDeCj.obtenerConApiKey(anyString())).thenReturn(
                new CjToken(TOKEN_LARGO, REFRESCO, OPEN_ID, Instant.now().plus(Duration.ofDays(90))));
        servicio.tokenVigente();

        assertThat(servicio.openId())
                .as("CJ da tres segundos para responder a un aviso: leerlo no puede costar otra llamada")
                .isEqualTo(OPEN_ID);
    }
}
