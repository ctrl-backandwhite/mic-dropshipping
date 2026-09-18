package com.nexaplatform.dropshipping.infrastructure.security;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSessionRevocationFilterTest {

    @Mock
    private DeviceSessionService deviceSessionService;

    @InjectMocks
    private DeviceSessionRevocationFilter filter;

    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest("GET", "/api/me");
        res = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "user@test.com", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void rejects_with_401_and_clears_context_when_device_session_revoked() throws Exception {
        authenticate();
        req.getSession(true); // ensure a session exists so invalidate() runs
        when(deviceSessionService.isRevoked(req)).thenReturn(true);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("SESSION_REVOKED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void continues_chain_when_authenticated_and_not_revoked() throws Exception {
        authenticate();
        when(deviceSessionService.isRevoked(req)).thenReturn(false);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void anonymous_request_passes_through_without_querying_service() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(deviceSessionService);
    }

    @Test
    void unauthenticated_request_passes_through_without_querying_service() throws Exception {
        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(deviceSessionService, never()).isRevoked(any());
    }

    /**
     * Un dispositivo revocado NO puede invalidar la sesión bajo los pies de la petición en curso.
     *
     * <p>Qué se rompía: al llamar a {@code session.invalidate()} aquí, cualquier OTRA petición que
     * compartiera esa sesión —y una ficha de producto lanza cinco a la vez— reventaba al terminar con
     * «Session was invalidated». Y el fallo no se quedaba ahí: el manejador global de errores tampoco
     * podía escribir su JSON, así que el navegador recibía una respuesta corrupta y enseñaba «no se ha
     * podido cargar este producto» en vez de mandar a identificarse.
     *
     * <p>Rechazar con 401 y limpiar el contexto basta: el filtro corta TODAS las peticiones mientras el
     * dispositivo siga revocado, así que invalidar la sesión no añadía seguridad, solo daños colaterales.
     */
    @Test
    void unDispositivoRevocadoSeRechazaSinInvalidarLaSesionEnCurso() throws Exception {
        authenticate();
        req.setSession(new org.springframework.mock.web.MockHttpSession());
        org.springframework.mock.web.MockHttpSession sesion =
                (org.springframework.mock.web.MockHttpSession) req.getSession(false);
        when(deviceSessionService.isRevoked(any())).thenReturn(true);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(sesion.isInvalid()).as("la sesión NO se invalida a mitad de petición").isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("pero deja de estar autenticado").isNull();
        verifyNoInteractions(chain);
    }
}
