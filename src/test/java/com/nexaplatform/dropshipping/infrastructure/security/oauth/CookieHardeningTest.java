package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atributos de las cookies que el backend deja en el navegador.
 *
 * <p>Un escaneo de OWASP ZAP levantó «cookie sin atributo SameSite» en {@code /login}. La cookie de sesión
 * ({@code JSESSIONID}) sí lo llevaba —vía {@code server.servlet.session.cookie.*}—; la que salía desnuda
 * era {@code XSRF-TOKEN}, porque {@link CookieCsrfTokenRepository} no pone {@code SameSite} salvo que se
 * le pase un customizer, y {@code login.html} la provoca al renderizar el campo {@code _csrf}.
 *
 * <p>Estos tests fallan si alguien revierte el customizer o borra la configuración de la cookie de sesión
 * del YAML, que es justo como reaparecería el hallazgo.
 *
 * <p>Nota sobre el otro hallazgo del mismo informe ({@code SPRING_CSRF_PROTECTION_DISABLED} en
 * {@code BffSecurityConfig} y {@code ResourceServerConfig}): es falso positivo porque esas dos cadenas son
 * {@code STATELESS} y no leen la {@code HttpSession}, de modo que ninguna ruta con efectos se autentica por
 * cookie. El razonamiento completo está en el javadoc de cada cadena, y su comportamiento (401 sin Bearer)
 * lo cubren {@code BffEndpointAuthorizationIT} y {@code PartnerAndEdgeAuthorizationIT}.
 */
class CookieHardeningTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    /** Guarda un token CSRF con el repositorio configurado y devuelve la cookie resultante. */
    private Cookie cookieCsrfCon(boolean entornoHttps) {
        DefaultSecurityConfig config = new DefaultSecurityConfig();
        ReflectionTestUtils.setField(config, "secureCookies", entornoHttps);
        CookieCsrfTokenRepository repository = config.csrfTokenRepository();

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);

        Cookie cookie = response.getCookie(CSRF_COOKIE);
        assertThat(cookie).as("el repositorio debe emitir la cookie %s", CSRF_COOKIE).isNotNull();
        return cookie;
    }

    /**
     * El arreglo del hallazgo de ZAP: la cookie CSRF sale con {@code SameSite=Lax}, así que el navegador no
     * la envía en peticiones cross-site con efectos.
     */
    @Test
    void laCookieCsrfLlevaSameSiteLax() {
        assertThat(cookieCsrfCon(false).getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookieCsrfCon(true).getAttribute("SameSite")).isEqualTo("Lax");
    }

    /** Donde hay HTTPS (dev/pre/pro) la cookie CSRF viaja marcada como {@code Secure}. */
    @Test
    void laCookieCsrfEsSecureEnEntornosHttps() {
        assertThat(cookieCsrfCon(true).getSecure()).isTrue();
    }

    /**
     * En local sobre HTTP no se marca {@code Secure}: el navegador descartaría la cookie y el formulario de
     * login dejaría de poder validar el token. Nunca se degrada nada — solo se deja el valor que deduce
     * Spring Security de la propia petición.
     */
    @Test
    void enLocalSobreHttpLaCookieCsrfNoSeMarcaSecure() {
        assertThat(cookieCsrfCon(false).getSecure()).isFalse();
    }

    /**
     * Sigue SIN {@code HttpOnly} A PROPÓSITO: el patrón de doble envío necesita que el JavaScript del
     * cliente lea el token para reenviarlo en {@code X-XSRF-TOKEN}. Es el token CSRF, no la sesión (esa sí
     * es {@code HttpOnly}, ver abajo). Se fija por test para que nadie lo «arregle» y rompa el login.
     */
    @Test
    void laCookieCsrfSigueSiendoLegiblePorJavaScript() {
        assertThat(cookieCsrfCon(true).isHttpOnly()).isFalse();
    }

    /**
     * La cookie de SESIÓN sale endurecida por configuración: {@code SameSite=Lax} y {@code HttpOnly} en
     * todos los entornos, y {@code Secure} en los que van por HTTPS. Spring Boot la aplica al
     * {@code JSESSIONID} desde estas propiedades.
     */
    @Test
    void laCookieDeSesionEstaEndurecidaEnLaConfiguracion() {
        assertThat(propiedad("application.yml", "server.servlet.session.cookie.same-site"))
                .isEqualTo("lax");
        assertThat(propiedad("application.yml", "server.servlet.session.cookie.http-only"))
                .isEqualTo(true);

        for (String perfil : List.of("application-dev.yml", "application-pre.yml", "application-pro.yml")) {
            assertThat(propiedad(perfil, "server.servlet.session.cookie.secure"))
                    .as("la cookie de sesión debe ser Secure en %s (va por HTTPS)", perfil).isEqualTo(true);
            assertThat(propiedad(perfil, "server.servlet.session.cookie.same-site"))
                    .as("SameSite no se debe perder al sobrescribir la sección en %s", perfil).isEqualTo("lax");
        }
    }

    /** Lee una propiedad de un YAML del classpath sin arrancar el contexto. */
    private Object propiedad(String fichero, String clave) {
        try {
            List<PropertySource<?>> fuentes = new YamlPropertySourceLoader()
                    .load(fichero, new ClassPathResource(fichero));
            for (PropertySource<?> fuente : fuentes) {
                Object valor = fuente.getProperty(clave);
                if (valor != null) {
                    return valor;
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + fichero, e);
        }
    }
}
