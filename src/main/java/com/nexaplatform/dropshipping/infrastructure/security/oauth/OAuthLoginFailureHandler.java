package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;

/**
 * Cierra un login social fallido: lo deja anotado en el registro del servidor y devuelve a la persona al
 * cliente por el que entró.
 *
 * <p>Lo del registro no es un adorno. Antes de existir esta clase el fallo se resolvía con un
 * {@code sendRedirect} y la excepción se tiraba sin más: la persona veía «no se pudo completar el inicio de
 * sesión con Google» y en el servidor no quedaba <b>ni una línea</b>, de modo que el motivo real había que
 * ir a buscarlo subiendo a mano el nivel de registro de Spring Security y reproduciendo el fallo. El motivo
 * lo trae el propio proveedor en el código de error de OAuth2 ({@code invalid_id_token},
 * {@code invalid_grant}, {@code authorization_request_not_found}…), que es exactamente el dato que hace
 * falta para saber si el problema está en la red, en las credenciales o en la sesión.
 *
 * <p>Ese detalle se queda <b>solo</b> en el servidor: a la dirección de vuelta va un código genérico
 * ({@code google}), porque el motivo técnico no le sirve a quien está delante y no tiene por qué acabar en
 * el historial del navegador ni en los registros de los proxys intermedios.
 *
 * <p>El destino de vuelta depende de quién arrancara el flujo —la web o la aplicación móvil—, que se anotó
 * en la sesión al empezar (ver {@link OAuthClientTargetFilter}); si no, la aplicación se quedaría esperando
 * en el navegador del sistema sin recuperar el foco.
 */
@Slf4j
public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    /** Código genérico con el que el cliente explica el fallo a la persona usuaria. */
    private static final String GENERIC_ERROR_CODE = "google";

    private final OAuthRedirectResolver redirects;

    public OAuthLoginFailureHandler(OAuthRedirectResolver redirects) {
        this.redirects = redirects;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        OAuthClientTarget target = OAuthClientTargetFilter.resolve(request);
        log.warn("::> [OAUTH2] Login social fallido ({}): {}", target.code(), describe(exception), exception);
        response.sendRedirect(redirects.error(target, GENERIC_ERROR_CODE));
    }

    /** Código y descripción que da el proveedor; para el resto de fallos, el mensaje de la excepción. */
    private static String describe(AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauthException)) {
            return exception.getMessage();
        }
        OAuth2Error error = oauthException.getError();
        String description = error.getDescription();
        return description == null || description.isBlank() ? error.getErrorCode()
                : error.getErrorCode() + " — " + description;
    }
}
