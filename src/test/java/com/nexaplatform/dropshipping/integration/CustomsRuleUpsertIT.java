package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dar de alta la regla aduanera de un país desde el panel.
 *
 * <p>Nace de un fallo que costó una certificación: al añadir el límite de aceptación del transportista
 * (v141) se crearon cuatro columnas {@code NOT NULL} con valor por defecto en la BASE, pero sin valor
 * por defecto en la ENTIDAD. Hibernate incluye todas las columnas en el INSERT, así que una regla nueva
 * viajaba con esos cuatro campos a nulo, la base lo rechazaba y el panel respondía 409 sin más
 * explicación. Editar una regla existente seguía funcionando —ya tenían valor—, y por eso pasó
 * inadvertido: solo fallaba dar de alta un país.
 *
 * <p>El valor por defecto de una columna solo lo aplica la base cuando la sentencia NO nombra esa
 * columna. Con un ORM que las nombra todas, el defecto hay que ponerlo también en el código.
 */
class CustomsRuleUpsertIT extends BaseIntegration {

    private static final String RUTA = "/api/admin/customs-rules/";

    private String tokenAdmin() {
        return jwt.userToken(java.util.UUID.randomUUID(), "admin@nx036.local", "ADMIN");
    }

    private static Map<String, Object> reglaMinima() {
        return Map.of("taxMode", "DDP", "deMinimisAmount", 150, "deMinimisCurrency", "EUR",
                "overThresholdPolicy", "BLOCK", "handlingFeeCents", 0, "handlingPercentBps", 0,
                "overThresholdSurchargeCents", 0, "dutyRateBps", 0, "active", true);
    }

    @Test
    @DisplayName("se puede dar de alta la regla de un país que no tenía ninguna")
    void seCreaLaReglaDeUnPaisNuevo() {
        client.put().uri(RUTA + "PT")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(reglaMinima())
                .exchange()
                .expectStatus().isOk();

        Integer filas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM country_customs_rule WHERE country_code = 'PT'", Integer.class);
        assertThat(filas).isEqualTo(1);
    }

    @Test
    @DisplayName("la regla nueva nace SIN límite del transportista, no con uno inventado")
    void laReglaNuevaNaceSinLimiteDelTransportista() {
        // Cero significa «sin dato», y sin dato no se bloquea a nadie. Rellenarlo con los 150 EUR de la UE
        // impondría a un país nuevo un tope que quizá su línea no tiene.
        client.put().uri(RUTA + "MX")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(reglaMinima())
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> fila = jdbcTemplate.queryForMap(
                "SELECT carrier_max_amount, carrier_max_currency, carrier_max_alt_amount,"
                        + " carrier_max_alt_currency, carrier_prepays_vat"
                        + " FROM country_customs_rule WHERE country_code = 'MX'");

        assertThat(((Number) fila.get("carrier_max_amount")).intValue()).isZero();
        assertThat(((Number) fila.get("carrier_max_alt_amount")).intValue()).isZero();
        assertThat(fila.get("carrier_max_currency")).isEqualTo("USD");
        assertThat(fila.get("carrier_max_alt_currency")).isEqualTo("USD");
        assertThat(fila.get("carrier_prepays_vat")).isEqualTo(false);
    }

    @Test
    @DisplayName("editar una regla no borra el límite del transportista que ya tenía")
    void editarNoPisaElLimiteYaSembrado() {
        // El formulario del panel no envía esos campos: son datos del contrato, no de la pantalla. Si el
        // guardado los pusiera a cero, la primera edición de España dejaría pasar pedidos que el
        // transportista rechaza, y el pedido se cobraría para morir en el almacén.
        jdbcTemplate.update("INSERT INTO country_customs_rule (id, country_code, tax_mode, de_minimis_amount,"
                + " de_minimis_currency, over_threshold_policy, handling_fee_cents, handling_percent_bps,"
                + " over_threshold_surcharge_cents, duty_rate_bps, vat_prepay_percent_bps,"
                + " per_article_fee_amount, per_article_fee_currency, carrier_max_amount, carrier_max_currency,"
                + " carrier_max_alt_amount, carrier_max_alt_currency, carrier_prepays_vat, active,"
                + " created_at, updated_at)"
                + " VALUES (gen_random_uuid(), 'ES', 'DDP', 150, 'EUR', 'BLOCK', 0, 0, 0, 0, 0,"
                + " 3.00, 'EUR', 150.00, 'EUR', 155.00, 'USD', true, true, now(), now())");

        client.put().uri(RUTA + "ES")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenAdmin()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(reglaMinima())
                .exchange()
                .expectStatus().isOk();

        Map<String, Object> fila = jdbcTemplate.queryForMap(
                "SELECT carrier_max_amount, carrier_max_alt_amount, carrier_prepays_vat,"
                        + " per_article_fee_amount FROM country_customs_rule WHERE country_code = 'ES'");

        assertThat(((Number) fila.get("carrier_max_amount")).intValue()).isEqualTo(150);
        assertThat(((Number) fila.get("carrier_max_alt_amount")).intValue()).isEqualTo(155);
        assertThat(fila.get("carrier_prepays_vat")).isEqualTo(true);
        assertThat(((Number) fila.get("per_article_fee_amount")).intValue())
                .as("el arancel por artículo tampoco se pisa").isEqualTo(3);
    }
}
