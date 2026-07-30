package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Revocación en caliente de los tokens de usuario del SPA.
 *
 * <p>El caso que motiva el filtro: degradar a un usuario de ADMIN a USER debe cortar su sesión en curso,
 * no dejarlo con permisos de administrador hasta que caduque su access token (60 min). Se verifica que un
 * token cuyo {@code iat} es anterior a la revocación del usuario se rechaza con 401, y que las rutas
 * públicas y los tokens vigentes pasan sin fricción.
 */
@ExtendWith(MockitoExtension.class)
class UserTokenRevocationFilterTest {

    @Mock
    private JwtRevocationService revocationService;
    @InjectMocks
    private UserTokenRevocationFilter filter;

    private MockHttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        res = new MockHttpServletResponse();
        chain = org.mockito.Mockito.mock(FilterChain.class);
    }

    private String token(String sub, long iatEpochSeconds) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub).issueTime(new Date(iatEpochSeconds * 1000L)).build();
        return new PlainJWT(claims).serialize();
    }

    private MockHttpServletRequest request(String path, String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    @Test
    void rechazaElTokenRevocadoEnRutaAutenticada() throws Exception {
        when(revocationService.isStillValid(eq("user-1"), anyLong())).thenReturn(false);

        filter.doFilterInternal(request("/api/admin/orders", token("user-1", 1000L)), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("TOKEN_REVOKED");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dejaPasarElTokenVigente() throws Exception {
        when(revocationService.isStillValid(eq("user-1"), anyLong())).thenReturn(true);

        filter.doFilterInternal(request("/api/me/orders", token("user-1", 9999L)), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noComprubaLasRutasPublicas() throws Exception {
        // El catálogo es navegación anónima: ni se mira la revocación, para no penalizarla.
        filter.doFilterInternal(request("/api/catalog/products", token("user-1", 1000L)), res, chain);

        verifyNoInteractions(revocationService);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sinTokenPasaYQueLoRechaceElResourceServer() throws Exception {
        filter.doFilterInternal(request("/api/me/orders", null), res, chain);

        verifyNoInteractions(revocationService);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static org.mockito.ArgumentMatcher<String> any() { return s -> true; }
    private static <T> T eq(T v) { return org.mockito.ArgumentMatchers.eq(v); }
}
