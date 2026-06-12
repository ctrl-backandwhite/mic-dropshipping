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
 * Belt-and-suspenders security headers. Spring Security adds the basics; we layer additional
 * defenses (CSP, Permissions-Policy, HSTS, Referrer-Policy) that protect against XSS, clickjacking,
 * MIME sniffing, and reduce the attack surface against AI-assisted credential phishing.
 *
 * <p>The CSP is strict (no inline scripts/styles); Swagger UI is exempted because springdoc serves
 * its own assets and would otherwise break in dev. In production, swagger should not be public.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP = "default-src 'self'; " + "img-src 'self' data: blob: https:; "
            + "font-src 'self' data:; " + "style-src 'self' 'unsafe-inline'; " + "script-src 'self'; "
            + "connect-src 'self'; " + "frame-ancestors 'none'; " + "base-uri 'self'; " + "form-action 'self'";

    private static final String CSP_SWAGGER = "default-src 'self'; img-src 'self' data: https:; font-src 'self' data:; "
            + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
            + "connect-src 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean swagger = path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");

        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        res.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=()");
        res.setHeader("Content-Security-Policy", swagger ? CSP_SWAGGER : CSP);
        // HSTS only when over HTTPS; behind a proxy the X-Forwarded-Proto header is honored
        if ("https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto")) || req.isSecure()) {
            res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        }
        // Block TRACE/TRACK
        String method = req.getMethod();
        if ("TRACE".equalsIgnoreCase(method) || "TRACK".equalsIgnoreCase(method)) {
            res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        chain.doFilter(req, res);
    }
}
