package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ServerToServerApiFilterTest {

    private final ServerToServerApiFilter filter = new ServerToServerApiFilter();

    @Test
    void allows_server_to_server_call_without_browser_headers() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void blocks_browser_request_with_origin_header_403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.addHeader("Origin", "https://example.com");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("BROWSER_NOT_ALLOWED");
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    void blocks_browser_request_with_sec_fetch_header_403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.addHeader("Sec-Fetch-Mode", "cors");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("BROWSER_NOT_ALLOWED");
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    void blank_origin_is_not_treated_as_browser() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.addHeader("Origin", "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void non_v1_paths_pass_through_even_from_browser() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        req.addHeader("Origin", "https://example.com");
        req.addHeader("Sec-Fetch-Site", "same-origin");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void rejection_message_is_localized_via_lang_query_param() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.addHeader("Origin", "https://example.com");
        req.setParameter("lang", "es");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains(BrowserBlockedMessage.ES.message());
    }

    @Test
    void rejection_message_falls_back_to_english_for_unknown_lang() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/products");
        req.addHeader("Origin", "https://example.com");
        req.addHeader("Accept-Language", "xx-YY,en;q=0.8");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains(BrowserBlockedMessage.EN.message());
    }
}
