package com.nexaplatform.dropshipping.infrastructure.integration.locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * ThreadLocal + filter for the active display locale, resolved from
 * {@code Accept-Language}. Supported locales: en (default), es, pt, zh.
 */
public final class LocaleHolder {

    public static final Set<String> SUPPORTED = Set.of("en", "es", "pt", "zh");
    private static final String DEFAULT = "en";
    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> DEFAULT);

    private LocaleHolder() {
    }

    public static String get() {
        String l = CURRENT.get();
        return l == null || l.isBlank() ? DEFAULT : l;
    }

    public static void set(String l) {
        if (l == null) {
            CURRENT.set(DEFAULT);
            return;
        }
        String norm = l.trim().toLowerCase().split("[-_]")[0];
        CURRENT.set(SUPPORTED.contains(norm) ? norm : DEFAULT);
    }

    public static void clear() {
        CURRENT.remove();
    }

    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE + 25)
    public static class Filter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            try {
                LocaleHolder.set(req.getHeader("Accept-Language"));
                chain.doFilter(req, res);
            } finally {
                LocaleHolder.clear();
            }
        }
    }
}
