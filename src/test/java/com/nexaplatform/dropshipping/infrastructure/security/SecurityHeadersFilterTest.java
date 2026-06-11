package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void adds_default_security_headers() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/storefront/catalog/products");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(res.getHeader("Permissions-Policy")).contains("geolocation=()");
        assertThat(res.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void uses_relaxed_csp_for_swagger_routes() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));

        assertThat(res.getHeader("Content-Security-Policy")).contains("unsafe-eval");
    }

    @Test
    void blocks_trace_method() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("TRACE", "/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(405);
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    void emits_hsts_when_request_is_secure() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/storefront/catalog/products");
        req.setSecure(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));
        assertThat(res.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
    }

    @Test
    void no_hsts_when_request_is_plain_http() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/storefront/catalog/products");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));
        assertThat(res.getHeader("Strict-Transport-Security")).isNull();
    }
}
