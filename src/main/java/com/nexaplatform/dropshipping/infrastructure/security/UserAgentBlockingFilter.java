package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Anti-cloning defense in depth: blocks named AI/training crawlers and known SEO scrapers by
 * user-agent (returns 403) so the API can't be harvested to clone the catalog — even if the backend
 * is reached directly, bypassing the nginx user-agent block.
 *
 * <p>Deliberately does NOT block Googlebot/Bingbot (kept for store SEO) nor generic clients
 * (curl/python/Go) — those are used by authenticated partner integrations. Browser-UA scrapers are
 * throttled by {@link RateLimitFilter} instead. Also tags responses as not-for-AI/indexing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class UserAgentBlockingFilter extends OncePerRequestFilter {

    /** Named AI/training crawlers and aggressive SEO bots — never legitimate users or partners. */
    private static final Pattern BLOCKED = Pattern.compile(
            "GPTBot|OAI-SearchBot|ChatGPT-User|ClaudeBot|Claude-Web|anthropic-ai|CCBot|Google-Extended|"
                    + "PerplexityBot|Perplexity-User|Bytespider|Amazonbot|Applebot-Extended|Meta-ExternalAgent|"
                    + "FacebookBot|Diffbot|DataForSeoBot|ImagesiftBot|Omgilibot|cohere-ai|YouBot|AI2Bot|Timpibot|"
                    + "Webzio|AhrefsBot|SemrushBot|MJ12bot|DotBot|BLEXBot|PetalBot|MauiBot|SeekportBot|serpstatbot|"
                    + "magpie-crawler",
            Pattern.CASE_INSENSITIVE);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // The API and admin must never be indexed nor used for AI training.
        res.setHeader("X-Robots-Tag", "noindex, nofollow, noai, noimageai");

        String ua = req.getHeader("User-Agent");
        if (ua != null && BLOCKED.matcher(ua).find()) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("text/plain;charset=UTF-8");
            res.getWriter().write("Forbidden: automated crawling of this site is not allowed.");
            return;
        }
        chain.doFilter(req, res);
    }
}
