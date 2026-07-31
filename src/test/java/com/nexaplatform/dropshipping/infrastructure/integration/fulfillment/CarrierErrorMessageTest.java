package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traducción de los rechazos del transportista.
 *
 * <p>YunExpress contesta en inglés y en chino; los avisos que recibe el responsable están en español. Sin
 * esta traducción el correo queda a medias entre dos idiomas y no dice qué hacer.
 */
class CarrierErrorMessageTest {

    @Test
    void traduceLosCodigosConocidosAlEspanol() {
        assertThat(CarrierErrorMessage.humanize(
                "02039171 Order rule verification failed: Weight : should not exceed 2KG"))
                .contains("no cumple las reglas del canal");
        assertThat(CarrierErrorMessage.humanize("02041002 The order does not exist"))
                .contains("todavía no reconoce esa guía");
        assertThat(CarrierErrorMessage.humanize("02060015 could not recommend a suitable price"))
                .contains("no ofrece tarifa");
    }

    @Test
    void unCodigoDesconocidoNoSeQuedaSinExplicacion() {
        assertThat(CarrierErrorMessage.humanize("99999999 algo nuevo"))
                .contains("detalle técnico");
    }

    @Test
    void sinMensajeTampocoSeRompe() {
        assertThat(CarrierErrorMessage.humanize(null)).contains("sin indicar motivo");
        assertThat(CarrierErrorMessage.humanize("")).contains("sin indicar motivo");
    }
}
