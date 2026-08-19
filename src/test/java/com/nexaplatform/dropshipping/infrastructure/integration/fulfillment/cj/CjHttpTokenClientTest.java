package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lectura de lo que CJ contesta al autenticarse.
 *
 * <p>Las respuestas de aquí son las <b>reales</b>, capturadas contra la API el 18-ago-2026 con la cuenta
 * del dueño. Se comprueban sobre el texto tal cual llega porque los tres detalles que rompen el parseo
 * no están en la documentación:
 *
 * <ul>
 *   <li>el {@code openId} viaja como <b>número</b> (33689), no como cadena, y es el secreto con el que
 *       se verifica la firma del webhook: leerlo mal no se nota hasta que llega el primer aviso;</li>
 *   <li>las fechas llegan con el desfase horario de China ({@code +08:00}), así que interpretarlas como
 *       UTC adelanta la renovación ocho horas;</li>
 *   <li>el token ocupa <b>566 caracteres</b>, lo que descarta guardarlo en un {@code VARCHAR(255)}.</li>
 * </ul>
 */
class CjHttpTokenClientTest {

    /** Respuesta literal de CJ, con los tokens acortados por no llenar el fichero. */
    private static final String RESPUESTA_REAL = """
            {"code":200,"result":true,"message":"Success",
             "data":{"openId":33689,
                     "accessToken":"API@CJ5236391@CJ:eyJhbGciOiJIUzI1NiJ9.token-de-acceso",
                     "accessTokenExpiryDate":"2027-02-14T13:31:52+08:00",
                     "refreshToken":"API@CJ5236391@CJ:eyJhbGciOiJIUzI1NiJ9.token-de-refresco",
                     "refreshTokenExpiryDate":"2027-02-14T13:31:52+08:00",
                     "createDate":"2026-08-18T13:31:52+08:00"},
             "requestId":"a1b2c3"}
            """;

    @Test
    @DisplayName("lee el token, el refresco y el openId de la respuesta real de CJ")
    void leeLaRespuestaReal() {
        CjToken token = CjHttpTokenClient.leer(RESPUESTA_REAL);

        assertThat(token.accessToken()).isEqualTo("API@CJ5236391@CJ:eyJhbGciOiJIUzI1NiJ9.token-de-acceso");
        assertThat(token.refreshToken()).isEqualTo("API@CJ5236391@CJ:eyJhbGciOiJIUzI1NiJ9.token-de-refresco");
        assertThat(token.openId())
                .as("viaja como número y hay que quedárselo como texto: es el secreto de la firma")
                .isEqualTo("33689");
    }

    @Test
    @DisplayName("la caducidad se interpreta con el huso de China, no como si fuera UTC")
    void respetaElHusoDeChina() {
        CjToken token = CjHttpTokenClient.leer(RESPUESTA_REAL);

        // 2027-02-14T13:31:52+08:00 son las 05:31:52 UTC del mismo día. Tomarlo por UTC adelantaría
        // ocho horas la fecha a partir de la cual ya no se puede refrescar.
        assertThat(token.refreshExpiraEn()).isEqualTo(Instant.parse("2027-02-14T05:31:52Z"));
    }

    @Test
    @DisplayName("un token de 566 caracteres se lee entero, sin recortar")
    void noRecortaElToken() {
        String largo = "A".repeat(566);
        String respuesta = """
                {"code":200,"result":true,"data":{"openId":1,"accessToken":"%s",
                 "refreshToken":"r","refreshTokenExpiryDate":"2027-02-14T13:31:52+08:00"}}
                """.formatted(largo);

        assertThat(CjHttpTokenClient.leer(respuesta).accessToken()).hasSize(566);
    }

    @Test
    @DisplayName("si CJ responde con error se avisa con su mensaje, no se devuelve un token vacío")
    void errorDeCj() {
        String error = """
                {"code":1600200,"result":false,"message":"apiKey is invalid","data":null}
                """;

        assertThatThrownBy(() -> CjHttpTokenClient.leer(error))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKey is invalid");
    }

    @Test
    @DisplayName("una respuesta sin datos no pasa por buena")
    void respuestaSinDatos() {
        assertThatThrownBy(() -> CjHttpTokenClient.leer("{\"code\":200,\"result\":true,\"data\":null}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sin fecha de caducidad del refresco no se inventa una: queda sin fecha")
    void sinFechaDeCaducidad() {
        String sinFecha = """
                {"code":200,"result":true,"data":{"openId":7,"accessToken":"a","refreshToken":"r"}}
                """;

        assertThat(CjHttpTokenClient.leer(sinFecha).refreshExpiraEn())
                .as("nula significa «no lo sé», y el servicio lo trata como refresco utilizable")
                .isNull();
    }
}
