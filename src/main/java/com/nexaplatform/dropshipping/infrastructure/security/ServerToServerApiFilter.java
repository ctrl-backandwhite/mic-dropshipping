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

/**
 * Forces the documented external API ({@code /api/v1/**}: partner, public storefront, inbound
 * integrations) to be consumed server-to-server only. Browser/frontend calls are rejected so a client
 * connecting to the platform must build their own backend (where the OAuth2 client secret can live
 * safely) instead of calling these endpoints directly from a webpage.
 *
 * <p>Detection: a real browser always attaches {@code Origin} (cross-origin / credentialed requests)
 * and the forbidden {@code Sec-Fetch-*} headers (which page JavaScript cannot remove) on fetch/XHR and
 * navigations. A server-side HTTP client sends neither. The app's own SPA does not call {@code /api/v1/**}
 * at runtime (only the storefront {@code /api/storefront/**} and admin APIs), so nothing internal breaks.
 *
 * <p>The rejection message is returned in the consumer's language (resolved by {@link #resolveLang},
 * via the {@code lang} query param or {@code Accept-Language} header), defaulting to English.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ServerToServerApiFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/v1/") && isBrowserOriginated(req)) {
            String message = BrowserBlockedMessage.forCode(resolveLang(req)).message();
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"code\":\"BROWSER_NOT_ALLOWED\",\"message\":\"" + message + "\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** True when the request looks browser-originated (Origin or any Sec-Fetch-* header present). */
    private boolean isBrowserOriginated(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return true;
        }
        // Sec-Fetch-* are forbidden headers: browsers always send them on fetch/XHR/navigation and JS
        // cannot strip them; server-side clients do not send them.
        return req.getHeader("Sec-Fetch-Site") != null || req.getHeader("Sec-Fetch-Mode") != null
                || req.getHeader("Sec-Fetch-Dest") != null;
    }

    /**
     * Language chosen by the consumer: the {@code lang} query param wins, then the first tag of the
     * {@code Accept-Language} header. Returns the 2-letter code (or null, handled as English downstream).
     */
    private String resolveLang(HttpServletRequest req) {
        String lang = req.getParameter("lang");
        if (lang == null || lang.isBlank()) {
            String accept = req.getHeader("Accept-Language");
            if (accept != null && !accept.isBlank()) {
                lang = accept.split(",")[0].trim();
            }
        }
        return (lang != null && lang.length() >= 2) ? lang.substring(0, 2) : null;
    }
}
