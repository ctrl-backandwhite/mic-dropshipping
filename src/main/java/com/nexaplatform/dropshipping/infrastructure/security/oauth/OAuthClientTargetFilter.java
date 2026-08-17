package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Recuerda qué cliente inició el login social.
 *
 * <p>El flujo sale del servidor hacia el proveedor y vuelve por otra petición distinta, así que el
 * parámetro {@code ?client=} de la llamada inicial se habría perdido para cuando hay que decidir a dónde
 * devolver los tokens. Se guarda en la sesión del flujo OAuth2, que es la misma que ya usa el manejador de
 * éxito para el enlace de cuentas pendiente.
 *
 * <p>Solo actúa al <b>arrancar</b> la autorización. En el callback del proveedor no se toca nada: si un
 * atacante añadiera el parámetro allí, cambiaría el destino de un flujo ya en marcha.
 */
@Slf4j
public class OAuthClientTargetFilter extends OncePerRequestFilter {

    /** Atributo de sesión donde viaja el cliente de origen durante el flujo. */
    public static final String CLIENT_TARGET_ATTRIBUTE = "OAUTH_CLIENT_TARGET";

    /** Parámetro con el que el cliente se identifica al arrancar el flujo. */
    public static final String CLIENT_PARAMETER = "client";

    private static final String AUTHORIZATION_PATH = "/oauth2/authorization/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().contains(AUTHORIZATION_PATH)) {
            OAuthClientTarget target = OAuthClientTarget.from(request.getParameter(CLIENT_PARAMETER));
            request.getSession(true).setAttribute(CLIENT_TARGET_ATTRIBUTE, target);
            log.debug("::> [OAUTH2] Inicio de login social desde el cliente {}", target.code());
        }
        chain.doFilter(request, response);
    }

    /** Cliente guardado al arrancar el flujo; {@link OAuthClientTarget#WEB} si no hay nada anotado. */
    public static OAuthClientTarget resolve(HttpServletRequest request) {
        if (request.getSession(false) == null) {
            return OAuthClientTarget.WEB;
        }
        Object stored = request.getSession(false).getAttribute(CLIENT_TARGET_ATTRIBUTE);
        return stored instanceof OAuthClientTarget target ? target : OAuthClientTarget.WEB;
    }
}
