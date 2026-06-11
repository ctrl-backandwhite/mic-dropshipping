package com.nexaplatform.dropshipping.infrastructure.integration.currency;

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
 * Reads the {@code X-Currency} request header and populates {@link CurrencyHolder}.
 * Order is HIGH_PRECEDENCE + 30 so this runs after security headers / rate limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CurrencyRequestFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Currency";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            CurrencyHolder.set(req.getHeader(HEADER));
            chain.doFilter(req, res);
        } finally {
            CurrencyHolder.clear();
        }
    }
}
