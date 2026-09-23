package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Esconde la cookie de sesión a las peticiones de la API, que son sin estado.
 *
 * <p>El problema que resuelve: el login social crea una sesión HTTP de verdad —la necesita, porque el
 * usuario sale hacia Google y vuelve por otra petición distinta—. Esa sesión vive en Redis con caducidad,
 * pero su cookie se emite con {@code Path=/}, así que el navegador la sigue mandando en TODAS las
 * peticiones, también en las de la API. Cuando la sesión caduca en Redis y la cookie sigue viva en el
 * navegador, Spring Session la carga al empezar la petición y no la encuentra al guardarla al cerrar la
 * respuesta: {@code RedisSessionRepository.save} lanza entonces {@code IllegalStateException: Session was
 * invalidated} desde dentro del volcado del búfer, cuando el controlador ya ha terminado.
 *
 * <p>Cómo se veía: la ficha de producto decía «no se ha podido cargar este producto» sobre productos que
 * existían, y al usuario le cerraba la sesión. Parecía aleatorio y no lo era: una ficha lanza media docena
 * de peticiones a la vez, así que en cuanto la sesión caducaba fallaban TODAS de golpe con 500 y el
 * cliente se iba a la pantalla de identificación. Reproducido acortando la caducidad de la sesión en Redis
 * a mitad de una ráfaga: seis peticiones, seis 500.
 *
 * <p>La corrección va a la causa y no al síntoma: si la cookie no llega, Spring Session no carga sesión
 * alguna y no tiene nada que guardar al cerrar la respuesta. No se pierde nada, porque estas cadenas ya son
 * {@code STATELESS} y se autentican con el token Bearer; la cookie que se les colaba no las servía para
 * nada. Solo se esconde la de sesión: el resto (CSRF, idioma, país) viaja intacto.
 *
 * <p>Va el PRIMERO de la cadena, antes que el filtro de Spring Session —que es quien lee la cookie—, y deja
 * fuera dos casos que sí necesitan la sesión: el flujo OAuth2 completo (que ni siquiera cuelga de
 * {@code /api}) y el alta de sesión en {@link #LOGIN}, donde el acceso completa el enlace con la cuenta de
 * Google que quedó pendiente al volver del proveedor.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiSinCookieDeSesionFilter extends OncePerRequestFilter {

    /** Cookie que emite Spring Session; es la única que se esconde. */
    static final String COOKIE_SESION = "SESSION";

    /** Prefijo de las rutas sin estado. */
    private static final String API = "/api/";

    /** Único punto de la API que sí lee la sesión: ahí se cierra el enlace pendiente con Google. */
    static final String LOGIN = "/api/auth/login";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(necesitaSesion(request) ? request : new SinCookieDeSesion(request), response);
    }

    /** Fuera de la API, y en el acceso, la cookie viaja tal cual. */
    private boolean necesitaSesion(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(API) || uri.equals(LOGIN);
    }

    /** Petición idéntica a la original salvo por la cookie de sesión, que no existe para quien la lea. */
    private static final class SinCookieDeSesion extends HttpServletRequestWrapper {

        private static final String CABECERA = "cookie";

        private SinCookieDeSesion(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Cookie[] getCookies() {
            Cookie[] originales = super.getCookies();
            if (originales == null) {
                return null;
            }
            Cookie[] limpias = Arrays.stream(originales).filter(c -> !COOKIE_SESION.equals(c.getName()))
                    .toArray(Cookie[]::new);
            return limpias.length == originales.length ? originales : limpias;
        }

        @Override
        public String getHeader(String name) {
            return CABECERA.equalsIgnoreCase(name) ? sinSesion(super.getHeader(name)) : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (!CABECERA.equalsIgnoreCase(name)) {
                return super.getHeaders(name);
            }
            List<String> valores = new ArrayList<>();
            for (Enumeration<String> e = super.getHeaders(name); e.hasMoreElements();) {
                String limpio = sinSesion(e.nextElement());
                if (limpio != null) {
                    valores.add(limpio);
                }
            }
            return Collections.enumeration(valores);
        }

        /**
         * Quita el par {@code SESSION=...} de una cabecera Cookie y respeta los demás. Devuelve
         * {@code null} si no queda ninguno, para no mandar una cabecera vacía.
         */
        private String sinSesion(String cabecera) {
            if (cabecera == null) {
                return null;
            }
            String resto = Arrays.stream(cabecera.split(";")).map(String::trim)
                    .filter(par -> !par.equals(COOKIE_SESION) && !par.startsWith(COOKIE_SESION + "="))
                    .filter(par -> !par.isEmpty()).reduce((a, b) -> a + "; " + b).orElse(null);
            return Objects.equals(resto, cabecera) ? cabecera : resto;
        }

        /** Sin cookie no hay sesión pedida: quien pregunte por el identificador no debe encontrarlo. */
        @Override
        public String getRequestedSessionId() {
            return null;
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return false;
        }
    }
}
