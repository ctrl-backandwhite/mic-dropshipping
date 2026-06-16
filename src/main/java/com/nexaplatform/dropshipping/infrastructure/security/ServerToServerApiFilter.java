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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class ServerToServerApiFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/v1/") && isBrowserOriginated(req)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":\"BROWSER_NOT_ALLOWED\",\"message\":"
                    + "\"This API must be consumed server-to-server. Build a backend and call it from there; "
                    + "direct browser/frontend calls are not allowed.\"}");
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
}
