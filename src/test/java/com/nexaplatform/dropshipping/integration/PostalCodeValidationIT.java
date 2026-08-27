package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El código postal tiene que ser el del país elegido, y se comprueba en el servidor.
 *
 * <p>Nace de un agujero real: los destinos que el transportista no sirve se comparan por número
 * —Baleares es 07000-07999—, así que escribir «07001A» dejaba el código fuera de toda comparación y
 * el pedido pasaba. Se cobraba, y al despachar no había forma de emitir la guía. Validar el formato en
 * el formulario no basta: quien quiera saltárselo llama a la API directamente, que es justo lo que
 * hacen estas pruebas.
 */
class PostalCodeValidationIT extends BaseIntegration {

    private static final String DIRECCIONES = "/api/me/addresses";
    private static final String MIGRACION = "db/changelog/schema-v143-destinos-no-servibles.sql";

    private UUID userId;
    private String token;

    @BeforeEach
    void prepararUsuarioYZonas() throws IOException {
        userId = UUID.randomUUID();
        token = jwt.userToken(userId, "cp@nx036.local", "USER");
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())", userId, "cp@nx036.local");
        // La base de los IT se vacía entre pruebas: se repone la lista de zonas excluidas con el mismo
        // fichero que se despliega (ver UnserviceableZoneIT).
        jdbcTemplate.execute(new String(new ClassPathResource(MIGRACION).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("no se puede guardar una dirección con un código postal que ese país no usa")
    void unCodigoPostalConLetraNoSeGuarda() {
        // «07001A» es el que esquivaba el bloqueo de Baleares: no es un código español.
        crearDireccion("ES", "07001A").expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("tampoco se puede guardar el código de otro país")
    void unCodigoDeOtroPaisNoSeGuarda() {
        crearDireccion("ES", "1000-001").expectStatus().isEqualTo(422);
        crearDireccion("DE", "SW1A 1AA").expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("en un país con formato conocido, la dirección sin código postal se rechaza")
    void sinCodigoPostalNoSeGuarda() {
        // Sin él no se puede saber si el destino es servible, y el transportista no admite el envío.
        crearDireccion("ES", null).expectStatus().isEqualTo(422);
        crearDireccion("ES", "").expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("una dirección correcta se guarda, con el formato que use cada país")
    void unaDireccionCorrectaSeGuarda() {
        crearDireccion("ES", "28001").expectStatus().isCreated();
        crearDireccion("PT", "1000-001").expectStatus().isCreated();
        crearDireccion("NL", "1012 AB").expectStatus().isCreated();
        crearDireccion("GB", "sw1a 1aa").expectStatus().isCreated();
    }

    @Test
    @DisplayName("un país cuyo formato no conocemos se guarda igual: ante la duda no se bloquea")
    void unPaisSinFormatoConocidoSeGuarda() {
        // Hong Kong no usa código postal. Exigirle uno impediría comprar a quien tiene la dirección bien.
        crearDireccion("HK", null).expectStatus().isCreated();
    }

    @Test
    @DisplayName("editar una dirección tampoco deja colar un código postal inválido")
    void alEditarTampocoCuela() {
        String creada = crearDireccion("ES", "28001").expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        String id = idDe(creada);

        client.put().uri(DIRECCIONES + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo("ES", "07001A"))
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    /** El identificador de la dirección recién creada, tal como lo devuelve la API. */
    private static String idDe(String respuesta) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(respuesta);
        if (!m.find()) {
            throw new IllegalStateException("La respuesta no trae id: " + respuesta);
        }
        return m.group(1);
    }

    private WebTestClient.ResponseSpec crearDireccion(String pais, String cp) {
        return client.post().uri(DIRECCIONES)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo(pais, cp))
                .exchange();
    }

    private static String cuerpo(String pais, String cp) {
        String cpJson = cp == null ? "null" : "\"" + cp + "\"";
        return """
                {"fullName":"Comprador de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","postalCode":%s,"country":"%s","isDefault":false}
                """.formatted(cpJson, pais);
    }
}
