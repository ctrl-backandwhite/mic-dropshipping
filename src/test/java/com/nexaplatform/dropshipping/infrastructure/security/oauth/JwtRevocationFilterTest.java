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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtRevocationFilterTest {

    private static final String PARTNER_PATH = "/api/v1/partner/orders";

    @Mock
    private JwtRevocationService revocationService;

    @InjectMocks
    private JwtRevocationFilter filter;

    private MockHttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        res = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    /** Builds an unsigned JWT carrying the given subject and issued-at instant. */
    private String token(String sub, long iatEpochSeconds) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issueTime(new Date(iatEpochSeconds * 1000L))
                .build();
        return new PlainJWT(claims).serialize();
    }

    private MockHttpServletRequest partnerRequest(String bearer) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", PARTNER_PATH);
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        return req;
    }

    @Test
    void rejects_revoked_token_with_401_and_does_not_continue_chain() throws Exception {
        long iat = 1_000L;
        MockHttpServletRequest req = partnerRequest(token("client-1", iat));
        when(revocationService.isStillValid("client-1", iat)).thenReturn(false);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).contains("invalid_token");
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("TOKEN_REVOKED");
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void continues_chain_when_token_still_valid() throws Exception {
        long iat = 2_000L;
        MockHttpServletRequest req = partnerRequest(token("client-2", iat));
        when(revocationService.isStillValid("client-2", iat)).thenReturn(true);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void non_partner_path_passes_through_without_checking_revocation() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        req.addHeader("Authorization", "Bearer " + token("client-3", 3_000L));

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(revocationService);
    }

    @Test
    void partner_path_without_bearer_passes_through() throws Exception {
        MockHttpServletRequest req = partnerRequest(null);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verifyNoInteractions(revocationService);
    }

    @Test
    void malformed_token_is_ignored_and_chain_continues() throws Exception {
        MockHttpServletRequest req = partnerRequest("not-a-real-jwt");

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        verify(revocationService, never()).isStillValid(anyString(), anyLong());
    }
}
