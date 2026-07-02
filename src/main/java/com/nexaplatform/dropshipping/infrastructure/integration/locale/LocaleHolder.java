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

    // Los 8 idiomas soportados por la plataforma (mismo conjunto que la i18n del front/emails/factura).
    public static final Set<String> SUPPORTED = Set.of("en", "es", "pt", "zh", "fr", "de", "it", "nl");
    private static final String DEFAULT = "es";
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
                // Preferimos el idioma SELECCIONADO en la web (header X-Lang); si no viene, el del navegador.
                String selected = req.getHeader("X-Lang");
                LocaleHolder.set(selected != null && !selected.isBlank() ? selected : req.getHeader("Accept-Language"));
                chain.doFilter(req, res);
            } finally {
                LocaleHolder.clear();
            }
        }
    }
}
