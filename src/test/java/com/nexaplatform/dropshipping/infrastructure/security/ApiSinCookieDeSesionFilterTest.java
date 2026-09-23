package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * El filtro esconde la cookie de sesión a las rutas sin estado, que es lo que evitaba el
 * {@code IllegalStateException: Session was invalidated} al cerrar la respuesta.
 */
class ApiSinCookieDeSesionFilterTest {

    private final ApiSinCookieDeSesionFilter subject = new ApiSinCookieDeSesionFilter();

    /** Deja pasar la petición y devuelve la que de verdad ha visto el resto de la cadena. */
    private HttpServletRequest through(MockHttpServletRequest request) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        subject.doFilter(request, new MockHttpServletResponse(), chain);
        var captor = forClass(HttpServletRequest.class);
        verify(chain).doFilter(captor.capture(), org.mockito.ArgumentMatchers.any());
        return captor.getValue();
    }

    private MockHttpServletRequest peticion(String uri, Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        // setCookies ya deriva por su cuenta la cabecera Cookie; añadirla a mano la duplicaría.
        request.setCookies(cookies);
        return request;
    }

    @Test
    @DisplayName("en la API la cookie de sesión no llega: ni en getCookies, ni en la cabecera, ni como id pedido")
    void escondeLaCookieEnLaApi() throws Exception {
        HttpServletRequest visto = through(
                peticion("/api/catalog/products", new Cookie("SESSION", "abc"), new Cookie("XSRF-TOKEN", "xyz")));

        assertThat(visto.getCookies()).extracting(Cookie::getName).containsExactly("XSRF-TOKEN");
        assertThat(visto.getHeader("Cookie")).isEqualTo("XSRF-TOKEN=xyz");
        assertThat(Collections.list(visto.getHeaders("Cookie"))).containsExactly("XSRF-TOKEN=xyz");
        assertThat(visto.getRequestedSessionId()).isNull();
        assertThat(visto.isRequestedSessionIdValid()).isFalse();
    }

    @Test
    @DisplayName("si la sesión era la única cookie, no se manda una cabecera vacía")
    void sinCabeceraCuandoSoloHabiaSesion() throws Exception {
        HttpServletRequest visto = through(peticion("/api/me", new Cookie("SESSION", "abc")));

        assertThat(visto.getCookies()).isEmpty();
        assertThat(visto.getHeader("Cookie")).isNull();
        assertThat(Collections.list(visto.getHeaders("Cookie"))).isEmpty();
    }

    @Test
    @DisplayName("el acceso sí ve la sesión: ahí se cierra el enlace pendiente con Google")
    void elLoginConservaLaSesion() throws Exception {
        HttpServletRequest visto = through(peticion(ApiSinCookieDeSesionFilter.LOGIN, new Cookie("SESSION", "abc")));

        assertThat(visto.getCookies()).extracting(Cookie::getName).containsExactly("SESSION");
        assertThat(visto.getHeader("Cookie")).isEqualTo("SESSION=abc");
    }

    @Test
    @DisplayName("fuera de la API no se toca nada: el flujo OAuth2 necesita su sesión")
    void fueraDeLaApiNoSeToca() throws Exception {
        HttpServletRequest visto = through(peticion("/oauth2/authorization/google", new Cookie("SESSION", "abc")));

        assertThat(visto.getCookies()).extracting(Cookie::getName).containsExactly("SESSION");
    }

    @Test
    @DisplayName("una petición de API sin cookies pasa sin romperse")
    void sinCookiesNoRompe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/geo");
        request.setRequestURI("/api/geo");

        HttpServletRequest visto = through(request);

        assertThat(visto.getCookies()).isNull();
        assertThat(visto.getRequestedSessionId()).isNull();
    }
}
