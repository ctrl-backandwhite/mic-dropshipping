package com.nexaplatform.dropshipping.api.dto.in;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un campo primitivo OPCIONAL que no viene en el JSON no puede tumbar la petición.
 *
 * <p>Spring Boot 4 trae {@code FAIL_ON_NULL_FOR_PRIMITIVES} activado. Con Lombok {@code @AllArgsConstructor}
 * y los nombres de parámetro compilados, Jackson deserializa por el constructor completo: un campo ausente
 * llega como {@code null} y, al ser primitivo, la petición entera se rechaza con "JSON inválido".
 *
 * <p>Esto dejó el <b>login inutilizable</b> para cualquier cliente que omitiera {@code linkSocial} —un
 * campo opcional del flujo social— y afectaba igual al checkout. Aquí se fija el comportamiento correcto:
 * ausente = valor por defecto.
 */
class OptionalPrimitiveFieldsTest {

    /** Mismo ajuste que aplica application.yml (spring.jackson.deserialization). */
    private final ObjectMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    @Test
    void elLoginSeDeserializaSinElCampoOpcionalLinkSocial() throws IOException {
        LoginDtoIn dto = mapper.readValue("{\"email\":\"a@b.com\",\"password\":\"secreto123\"}", LoginDtoIn.class);

        assertThat(dto.getEmail()).isEqualTo("a@b.com");
        assertThat(dto.isLinkSocial()).isFalse();
    }

    @Test
    void elLoginSigueLeyendoElCampoCuandoSiViene() throws IOException {
        LoginDtoIn dto = mapper.readValue("{\"email\":\"a@b.com\",\"password\":\"secreto123\",\"linkSocial\":true}",
                LoginDtoIn.class);

        assertThat(dto.isLinkSocial()).isTrue();
    }

    @Test
    void unNullExplicitoTampocoRompe() throws IOException {
        LoginDtoIn dto = mapper.readValue("{\"email\":\"a@b.com\",\"password\":\"secreto123\",\"linkSocial\":null}",
                LoginDtoIn.class);

        assertThat(dto.isLinkSocial()).isFalse();
    }

    @Test
    void elCheckoutTambienToleraSusPrimitivosAusentes() throws IOException {
        // MeCheckoutDtoIn lleva primitivos: el mismo fallo dejaría al comprador sin poder pagar.
        MeCheckoutDtoIn dto = mapper.readValue("{}", MeCheckoutDtoIn.class);

        assertThat(dto).isNotNull();
    }
}
