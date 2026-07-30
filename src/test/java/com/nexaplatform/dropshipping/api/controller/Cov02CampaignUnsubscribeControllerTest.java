package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.infrastructure.campaign.MarketingUnsubscribeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de la página de baja/alta de los correos de campaña: idioma del texto, adónde lleva el botón
 * y —lo importante— que un token inválido NO confirme una baja que no ha ocurrido.
 *
 * <p>Se comprueban las variables que se le pasan a la plantilla, que es lo que decide el contenido.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02CampaignUnsubscribeControllerTest {

    private static final String BACKEND = "http://backend:18082";
    private static final String TIENDA = "http://tienda:3003";
    private static final String PLANTILLA = "pages/unsubscribe-result";

    @Mock
    private MarketingUnsubscribeService unsubscribeService;

    @Mock
    private TemplateEngine templateEngine;

    private CampaignUnsubscribeController controller;

    @BeforeEach
    void setUp() {
        controller = new CampaignUnsubscribeController(unsubscribeService, templateEngine);
        ReflectionTestUtils.setField(controller, "backendBaseUrl", BACKEND);
        ReflectionTestUtils.setField(controller, "storefrontBaseUrl", TIENDA);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>pagina</html>");
    }

    /** Variables con las que se ha pintado la plantilla en la última llamada. */
    private IContext contexto() {
        ArgumentCaptor<IContext> captor = ArgumentCaptor.forClass(IContext.class);
        verify(templateEngine).process(eq(PLANTILLA), captor.capture());
        return captor.getValue();
    }

    @Test
    void laBajaValidaMuestraLaConfirmacionEnEspanolConElEnlaceDeVuelta() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        ResponseEntity<String> respuesta = controller.unsubscribe("tok", "es");

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(respuesta.getBody()).isEqualTo("<html>pagina</html>");
        IContext ctx = contexto();
        assertThat(ctx.getVariable("title")).isEqualTo("Te has dado de baja");
        assertThat(ctx.getVariable("actionLabel")).isEqualTo("Volver a recibir novedades");
        assertThat(ctx.getVariable("actionUrl").toString())
                .startsWith(BACKEND + "/api/campaigns/resubscribe?lang=es&token=");
    }

    @Test
    void elTokenViajaCodificadoEnElEnlaceDeReactivacion() {
        // Un token HMAC en base64 lleva "+" y "/": sin codificar, el "+" llega como espacio al backend
        // y el enlace de "volver a suscribirme" no funcionaría nunca.
        when(unsubscribeService.unsubscribe("a+b/c=")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("a+b/c=", "es");

        assertThat(contexto().getVariable("actionUrl").toString()).endsWith("token=a%2Bb%2Fc%3D");
    }

    @Test
    void elIdiomaPedidoSeRespetaEnElTextoYEnElEnlace() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("tok", "en");

        IContext ctx = contexto();
        assertThat(ctx.getVariable("lang")).isEqualTo("en");
        assertThat(ctx.getVariable("title")).isEqualTo("You're unsubscribed");
        assertThat(ctx.getVariable("actionUrl").toString()).contains("lang=en");
    }

    @Test
    void unIdiomaNuloCaeEnEspanol() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("tok", null);

        assertThat(contexto().getVariable("lang")).isEqualTo("es");
    }

    @Test
    void unIdiomaEnBlancoCaeEnEspanol() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("tok", "   ");

        assertThat(contexto().getVariable("lang")).isEqualTo("es");
    }

    @Test
    void elIdiomaSeNormalizaSinEspaciosYEnMinusculas() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("tok", "  DE  ");

        IContext ctx = contexto();
        assertThat(ctx.getVariable("lang")).isEqualTo("de");
        assertThat(ctx.getVariable("title")).isEqualTo("Du bist abgemeldet");
    }

    @Test
    void unIdiomaNoSoportadoSeMuestraEnEspanol() {
        when(unsubscribeService.unsubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.unsubscribe("tok", "ru");

        assertThat(contexto().getVariable("title")).isEqualTo("Te has dado de baja");
    }

    @Test
    void unTokenInvalidoNoConfirmaUnaBajaQueNoHaOcurrido() {
        // Si dijera "te has dado de baja", el usuario creería estar fuera y seguiría recibiendo correos.
        when(unsubscribeService.unsubscribe("falso")).thenReturn(Optional.empty());

        ResponseEntity<String> respuesta = controller.unsubscribe("falso", "es");

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        IContext ctx = contexto();
        assertThat(ctx.getVariable("title")).isEqualTo("Enlace no válido");
        assertThat(ctx.getVariable("actionUrl")).isEqualTo(TIENDA);
    }

    @Test
    void laReactivacionValidaLlevaDeVueltaALaTienda() {
        when(unsubscribeService.resubscribe("tok")).thenReturn(Optional.of(new UserEntity()));

        controller.resubscribe("tok", "es");

        IContext ctx = contexto();
        assertThat(ctx.getVariable("title")).isEqualTo("Suscripción reactivada");
        assertThat(ctx.getVariable("actionLabel")).isEqualTo("Ir a NX036");
        assertThat(ctx.getVariable("actionUrl")).isEqualTo(TIENDA);
    }

    @Test
    void laReactivacionConTokenInvalidoMuestraLaPaginaDeEnlaceCaducado() {
        when(unsubscribeService.resubscribe("falso")).thenReturn(Optional.empty());

        controller.resubscribe("falso", "pt");

        assertThat(contexto().getVariable("title")).isEqualTo("Ligação inválida");
    }
}
