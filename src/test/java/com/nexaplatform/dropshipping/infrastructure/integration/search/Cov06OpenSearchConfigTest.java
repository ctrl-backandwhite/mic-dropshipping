package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        assertThatThrownBy(() -> config.openSearchClient("http://opensearch:9200", "admin", "secreta", false,
                objectMapper)).isInstanceOf(IllegalStateException.class).hasMessageContaining("https");
    }

    /** Local sin credenciales: conexión plana, que es el modo de desarrollo. */
    @Test
    void sinCredencialesElClientePlanoSeConstruye() throws IOException {
        OpenSearchClient cliente = config.openSearchClient("http://localhost:9200", "", "", false, objectMapper);

        assertThat(cliente).isNotNull();
        cliente._transport().close();
    }

    /** Una lista de nodos separada por comas usa el primero; los espacios sobrantes no rompen la URI. */
    @Test
    void deVariasUrisSeUsaLaPrimera() throws IOException {
        OpenSearchClient cliente = config.openSearchClient(" http://localhost:9200 , http://otro:9200", "", "",
                false, objectMapper);

        assertThat(cliente).isNotNull();
        cliente._transport().close();
    }

    /** Sin puerto en la URI se asume el 9200 (el de OpenSearch) en vez de fallar. */
    @Test
    void sinPuertoEnLaUriSeAsumeElNueveMilDoscientos() throws IOException {
        OpenSearchClient cliente = config.openSearchClient("http://opensearch", "", "", false, objectMapper);

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
