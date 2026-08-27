package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientTargetFilterTest {

    private OAuthClientTargetFilter filter;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new OAuthClientTargetFilter();
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    private MockHttpServletRequest peticion(String uri, String cliente) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (cliente != null) {
            request.setParameter(OAuthClientTargetFilter.CLIENT_PARAMETER, cliente);
        }
        return request;
    }

    @Test
    void anotaLaAplicacionMovilAlArrancarElFlujo() throws ServletException, IOException {
        MockHttpServletRequest request = peticion("/oauth2/authorization/google", "mobile");

        filter.doFilter(request, response, chain);

        assertThat(OAuthClientTargetFilter.resolve(request)).isEqualTo(OAuthClientTarget.MOBILE);
    }

    @Test
    void sinParametroElClienteEsLaWeb() throws ServletException, IOException {
        MockHttpServletRequest request = peticion("/oauth2/authorization/google", null);

        filter.doFilter(request, response, chain);

        assertThat(OAuthClientTargetFilter.resolve(request)).isEqualTo(OAuthClientTarget.WEB);
    }

    @Test
    void unValorInventadoNoCambiaElDestino() throws ServletException, IOException {
        MockHttpServletRequest request = peticion("/oauth2/authorization/google", "https://atacante.example");

        filter.doFilter(request, response, chain);

        assertThat(OAuthClientTargetFilter.resolve(request)).isEqualTo(OAuthClientTarget.WEB);
    }

    @Test
    void elCallbackDelProveedorNoPuedeCambiarElDestinoDeUnFlujoEnMarcha() throws ServletException, IOException {
        // Un flujo arrancado desde la web debe terminar en la web. Si el parámetro se admitiera también
        // en la vuelta, bastaría con manipular esa URL para desviar los tokens a la aplicación.
        MockHttpServletRequest arranque = peticion("/oauth2/authorization/google", null);
        filter.doFilter(arranque, response, chain);

        MockHttpServletRequest vuelta = peticion("/login/oauth2/code/google", "mobile");
        vuelta.setSession(arranque.getSession());
        filter.doFilter(vuelta, response, new MockFilterChain());

        assertThat(OAuthClientTargetFilter.resolve(vuelta)).isEqualTo(OAuthClientTarget.WEB);
    }

    @Test
    void dejaPasarLaPeticionSiempre() throws ServletException, IOException {
        MockHttpServletRequest request = peticion("/api/me", null);
        MockFilterChain cadena = new MockFilterChain();

        filter.doFilter(request, response, cadena);

        assertThat(cadena.getRequest()).isSameAs(request);
    }
}
