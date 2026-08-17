package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CheckoutPreviewService;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ShippingQuoteService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El formulario de dirección pregunta al servidor qué código postal espera cada país.
 *
 * <p>La alternativa era copiar la tabla de formatos en el navegador, y entonces habría dos: la que
 * valida de verdad y la que avisa. Cuando se corrigiese una y no la otra, el formulario rechazaría
 * direcciones que el servidor acepta —o al revés, que es peor—. Aquí la regla es una sola y el front
 * la consulta.
 */
class PostalFormatEndpointTest {

    private final ShippingQuoteController controller = new ShippingQuoteController(
            mock(CheckoutPreviewService.class), mock(ShippingQuoteService.class), mock(CountryTaxService.class),
            mock(PricingService.class), mock(CurrencyRateService.class));

    @Test
    @DisplayName("un país conocido publica su patrón y un ejemplo")
    void unPaisConocidoPublicaSuPatron() {
        ShippingQuoteController.PostalFormatOut es = controller.postalFormat("ES").getBody();

        assertThat(es.required()).isTrue();
        assertThat(es.example()).isEqualTo("28001");
        assertThat("28001".matches(es.pattern())).isTrue();
        assertThat("07001A".matches(es.pattern())).as("el que esquivaba el bloqueo").isFalse();
    }

    @Test
    @DisplayName("el patrón que se publica es el mismo con el que se valida, con letras o guion incluidos")
    void elPatronPublicadoSirveParaValidarEnElFormulario() {
        ShippingQuoteController.PostalFormatOut pt = controller.postalFormat("PT").getBody();
        assertThat("1000-001".matches(pt.pattern())).isTrue();

        ShippingQuoteController.PostalFormatOut nl = controller.postalFormat("NL").getBody();
        assertThat("1012 AB".matches(nl.pattern())).isTrue();
    }

    @Test
    @DisplayName("un país cuyo formato no conocemos no exige nada")
    void unPaisDesconocidoNoExigeNada() {
        // Hong Kong no usa código postal: el formulario no debe pedirlo ni marcarlo en rojo.
        ShippingQuoteController.PostalFormatOut hk = controller.postalFormat("HK").getBody();

        assertThat(hk.required()).isFalse();
        assertThat(hk.pattern()).isEmpty();
        assertThat(hk.example()).isEmpty();
    }

    @Test
    @DisplayName("sin país no se inventa un formato")
    void sinPaisNoSeInventaNada() {
        assertThat(controller.postalFormat(null).getBody().required()).isFalse();
        assertThat(controller.postalFormat("").getBody().required()).isFalse();
    }
}
