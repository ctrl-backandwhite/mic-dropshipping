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
import java.util.List;
import java.util.Locale;

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

    /**
     * Named AI/training crawlers and aggressive SEO bots — never legitimate users or partners.
     *
     * <p>Es una lista de marcas literales, no una expresión regular: la alternancia de 34 ramas era
     * ilegible y obligaba al motor a probarlas todas en CADA petición. Un {@code contains} sobre el
     * user-agent ya en minúsculas hace exactamente lo mismo y se lee de un vistazo al añadir un bot.
     * Todas las marcas van en MINÚSCULAS porque la comparación se hace contra la cabecera normalizada.
     */
    private static final List<String> BLOCKED_AGENTS = List.of("gptbot", "oai-searchbot", "chatgpt-user", "claudebot",
            "claude-web", "anthropic-ai", "ccbot", "google-extended", "perplexitybot", "perplexity-user", "bytespider",
            "amazonbot", "applebot-extended", "meta-externalagent", "facebookbot", "diffbot", "dataforseobot",
            "imagesiftbot", "omgilibot", "cohere-ai", "youbot", "ai2bot", "timpibot", "webzio", "ahrefsbot",
            "semrushbot", "mj12bot", "dotbot", "blexbot", "petalbot", "mauibot", "seekportbot", "serpstatbot",
            "magpie-crawler");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // The API and admin must never be indexed nor used for AI training.
        res.setHeader("X-Robots-Tag", "noindex, nofollow, noai, noimageai");

        String ua = req.getHeader("User-Agent");
        if (isBlockedAgent(ua)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("text/plain;charset=UTF-8");
            res.getWriter().write("Forbidden: automated crawling of this site is not allowed.");
            return;
        }
        chain.doFilter(req, res);
    }

    /**
     * Coincidencia por subcadena e insensible a mayúsculas: los bots añaden versión y URL a su marca
     * ({@code "CCBot/2.0 (https://commoncrawl.org/faq/)"}), así que comparar la cabecera entera nunca
     * acertaría. Se normaliza con {@link Locale#ROOT} para que la lista siga funcionando con la
     * configuración regional turca, donde {@code "I"} no baja a {@code "i"}.
     */
    private static boolean isBlockedAgent(String userAgent) {
        if (userAgent == null) {
            return false;
        }
        String normalized = userAgent.toLowerCase(Locale.ROOT);
        return BLOCKED_AGENTS.stream().anyMatch(normalized::contains);
    }
}
