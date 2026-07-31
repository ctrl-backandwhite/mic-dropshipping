package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserAgentBlockingFilterTest {

    private final UserAgentBlockingFilter filter = new UserAgentBlockingFilter();

    @Test
    void blocks_named_ai_crawler_with_403_and_stops_chain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        req.addHeader("User-Agent", "Mozilla/5.0 (compatible; GPTBot/1.1; +https://openai.com/gptbot)");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentType()).contains("text/plain");
        assertThat(res.getContentAsString()).contains("Forbidden");
        assertThat(res.getHeader("X-Robots-Tag")).contains("noai");
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    void blocks_seo_scraper_case_insensitively() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/catalog");
        req.addHeader("User-Agent", "ahrefsbot/7.0");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        verify(chain, times(0)).doFilter(req, res);
    }

    @Test
    void allows_normal_browser_user_agent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        req.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0 Safari/537.36");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-Robots-Tag")).contains("noindex");
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void allows_request_without_user_agent_header() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }

    @Test
    void does_not_block_search_engine_bots_kept_for_seo() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/catalog/products");
        req.addHeader("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(req, res);
    }
}
