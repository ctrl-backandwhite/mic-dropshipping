package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un login social que falla tiene que <b>dejar dicho por qué</b>.
 *
 * <p>Esta clase nace de un fallo real que costó una mañana: el acceso con Google devolvía a la pantalla de
 * entrada con un «no se pudo completar» y en el registro del servidor no quedaba absolutamente nada, porque
 * el manejador de fallos se limitaba a redirigir y tiraba la excepción. El motivo verdadero —las claves
 * públicas de Google no se descargaban dentro del plazo— solo se pudo ver subiendo a mano el nivel de
 * registro de Spring Security y reproduciendo el fallo. Eso no puede volver a pasar.
 */
class OAuthLoginFailureHandlerTest {

    private static final String FRONT = "https://app.example.com";
    private static final String MOBILE = "nx036://auth/callback";

    private final OAuthLoginFailureHandler handler = new OAuthLoginFailureHandler(
            new OAuthRedirectResolver(FRONT, MOBILE));

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void capturarElRegistro() {
        logger = (Logger) LoggerFactory.getLogger(OAuthLoginFailureHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void soltarElRegistro() {
        logger.detachAppender(appender);
    }

    @Test
    void dejaEnElRegistroElCodigoYElDetalleQueDaElProveedor() throws IOException {
        AuthenticationException fallo = new OAuth2AuthenticationException(
                new OAuth2Error("invalid_id_token", "An error occurred while attempting to decode the Jwt: "
                        + "I/O error on GET request for \"https://www.googleapis.com/oauth2/v3/certs\": Read timed out",
                        null),
                "invalid_id_token");

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(), fallo);

        assertThat(appender.list).hasSize(1);
        String registrado = appender.list.get(0).getFormattedMessage();
        assertThat(registrado).contains("invalid_id_token").contains("Read timed out");
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void registraTambienUnFalloQueNoTraeCodigoDeOAuth() throws IOException {
        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new BadCredentialsException("credenciales no válidas"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("credenciales no válidas");
    }

    @Test
    void guardaLaExcepcionCompletaParaPoderSeguirLaPista() throws IOException {
        AuthenticationException fallo = new OAuth2AuthenticationException(new OAuth2Error("invalid_grant"),
                "invalid_grant");

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(), fallo);

        assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    void devuelveALaPantallaDeAccesoDeLaWeb() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_id_token"), "invalid_id_token"));

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONT + "/login?error=google");
    }

    @Test
    void devuelveAlEnlaceProfundoCuandoElFlujoLoArrancoLaAplicacionMovil() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(OAuthClientTargetFilter.CLIENT_TARGET_ATTRIBUTE,
                OAuthClientTarget.MOBILE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_id_token"), "invalid_id_token"));

        assertThat(response.getRedirectedUrl()).isEqualTo(MOBILE + "?error=google");
    }

    @Test
    void elMotivoTecnicoNuncaViajaEnLaDireccionDeVuelta() throws IOException {
        // Lo que se le cuenta al navegador es un código genérico: el detalle se queda en el servidor.
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_id_token", "Read timed out", null),
                        "invalid_id_token"));

        assertThat(response.getRedirectedUrl()).doesNotContain("timed out").doesNotContain("invalid_id_token");
    }
}
