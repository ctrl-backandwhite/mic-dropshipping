package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.client.opensearch.OpenSearchClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Construcción del cliente de OpenSearch. La regla que importa es de seguridad: las credenciales del
 * índice NUNCA pueden salir en claro por la red.
 */
class Cov06OpenSearchConfigTest {

    private OpenSearchConfig config;
    private ObjectMapper objectMapper;

    @BeforeEach
    void preparar() {
        config = new OpenSearchConfig();
        objectMapper = new ObjectMapper();
    }

    /**
     * Fail-closed: con usuario y contraseña configurados pero URI http, el arranque FALLA. Arrancar
     * igualmente enviaría la autenticación básica en claro en cada petición al índice.
     */
    @Test
    void conCredencialesYSinTlsElArranqueFalla() {
        assertThatThrownBy(
                () -> config.openSearchClient("http://opensearch:9200", "admin", "secreta", false, objectMapper))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    /**
     * Formas de la URI que el cliente sin credenciales (el modo de desarrollo) tiene que aceptar: una
     * sola URI; una lista de nodos separada por comas, de la que se usa la primera sin que los espacios
     * sobrantes rompan la URI; y una URI sin puerto, donde se asume el 9200 de OpenSearch en vez de fallar.
     */
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9200", " http://localhost:9200 , http://otro:9200", "http://opensearch"})
    void sinCredencialesElClientePlanoSeConstruyeSeaCualSeaLaFormaDeLaUri(String uris) throws IOException {
        OpenSearchClient cliente = config.openSearchClient(uris, "", "", false, objectMapper);

        assertThat(cliente).isNotNull();
        cliente._transport().close();
    }

    /**
     * El TLS relajado (confía en el certificado autofirmado del servicio interno) solo se activa cuando
     * se pide expresamente Y la URI es https: pedirlo sobre http no cambia nada.
     */
    @Test
    void elTlsRelajadoSoloAplicaEnHttps() throws IOException {
        OpenSearchClient conHttps = config.openSearchClient("https://opensearch:9200", "admin", "secreta", true,
                objectMapper);
        OpenSearchClient conHttp = config.openSearchClient("http://localhost:9200", "", "", true, objectMapper);

        assertThat(conHttps).isNotNull();
        assertThat(conHttp).isNotNull();
        conHttps._transport().close();
        conHttp._transport().close();
    }
}
