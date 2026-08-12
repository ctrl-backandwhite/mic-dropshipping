package com.nexaplatform.dropshipping.infrastructure.integration.pricing;

import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
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
 * Puebla {@link PricingCountryHolder} con el país efectivo del comprador para la resolución del margen por
 * país. Prioridad:
 * <ol>
 *   <li>{@code X-Country}: país efectivo que envía el front (país de registro del usuario logueado).</li>
 *   <li>Cabecera de país por IP del proxy/CDN ({@code CF-IPCountry} de Cloudflare, {@code X-Vercel-IP-Country}…)
 *       cuando el front no envía país (invitado).</li>
 * </ol>
 * Vacío = sin país → {@code MarginService} usa las reglas sin país (comportamiento base). Se limpia al final
 * del request para no contaminar el hilo del pool.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 31)
public class PricingCountryFilter extends OncePerRequestFilter {

    public static final String HEADER_COUNTRY = "X-Country";
    /** Cabeceras de país por IP que suelen inyectar los CDN/proxys; se leen si el front no manda país. */
    private static final String[] GEO_HEADERS = { "CF-IPCountry", "X-Vercel-IP-Country", "X-Geo-Country",
            "X-Country-Code" };

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            PricingCountryHolder.set(resolveCountry(req));
            chain.doFilter(req, res);
        } finally {
            PricingCountryHolder.clear();
        }
    }

    private static String resolveCountry(HttpServletRequest req) {
        String explicit = req.getHeader(HEADER_COUNTRY);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        for (String h : GEO_HEADERS) {
            String v = req.getHeader(h);
            // Algunos CDN mandan "XX" o "T1" (Tor) cuando no saben el país: se ignoran.
            if (v != null && v.length() == 2 && !"XX".equalsIgnoreCase(v) && !"T1".equalsIgnoreCase(v)) {
                return v;
            }
        }
        return null;
    }
}
