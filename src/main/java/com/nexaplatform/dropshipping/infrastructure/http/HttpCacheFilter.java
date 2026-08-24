package com.nexaplatform.dropshipping.infrastructure.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.io.IOException;

/**
 * Plan 300k req/min — Fase 4 (CDN):
 * Añade {@code Cache-Control}, {@code Vary} y {@code ETag} a las respuestas
 * GET de endpoints públicos (storefront + categorías + suppliers). Esto
 * permite que un CDN delante (Cloudflare/Bunny/CloudFront) cachee el grueso
 * del tráfico sin que llegue al backend.
 * <p>
 * Para el cálculo del ETag se delega en {@link ShallowEtagHeaderFilter} (sha
 * del cuerpo de la respuesta). Es "shallow" pero suficiente — el ahorro real
 * lo da el CDN; el ETag aquí es solo para revalidación condicional cuando el
 * cliente directo vuelve con {@code If-None-Match}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class HttpCacheFilter extends OncePerRequestFilter {

    private final ShallowEtagHeaderFilter etag = new ShallowEtagHeaderFilter();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String method = req.getMethod();
        String path = req.getRequestURI();
        boolean cacheable = "GET".equals(method) && isPublicReadable(path);

        if (cacheable) {
            // TTL agresivo en endpoints "fríos" (categorías, suppliers).
            // TTL corto en endpoints volátiles (listings, PDP) — el CDN sigue
            // siendo el responsable real del cache, esto es la directiva para él.
            res.setHeader("Cache-Control", cacheControlFor(path));
            // Estas combinaciones afectan al contenido devuelto. X-Country no es opcional: el margen se
            // aplica por país del comprador y el arancel adicional que enseña el catálogo sale del importe
            // por artículo de SU país. Sin declararlo, un intermediario puede servir a un comprador la
            // respuesta cacheada de otro país, con otro precio y otra promesa de aduana.
            res.setHeader("Vary", "Accept-Language, Accept-Encoding, X-Currency, X-Country");
            etag.doFilter(req, res, chain);
        } else {
            // Endpoints autenticados o de escritura no deben cachearse.
            if (path.startsWith("/api/")) {
                res.setHeader("Cache-Control", "no-store");
            }
            chain.doFilter(req, res);
        }
    }

    private boolean isPublicReadable(String path) {
        return path.startsWith("/api/catalog/") || path.startsWith("/api/warehouses")
                || path.startsWith("/api/v1/rate-limits");
    }

    private String cacheControlFor(String path) {
        // Categorías/suppliers cambian raro → 5 min + SWR 10 min
        if (path.contains("/categories") || path.contains("/suppliers") || path.contains("/warehouses")) {
            return "public, max-age=300, stale-while-revalidate=600";
        }
        // Listings y PDP — llevan PRECIOS sensibles al margen/moneda/tramos, que pueden cambiar en
        // CUALQUIER momento (activar/desactivar una regla, cambiar el %, crear una regla más específica,
        // o una nueva tasa de cambio). Con max-age el navegador servía el precio viejo de su caché hasta
        // 60s sin preguntar al backend → "activo el margen, recargo y sigue igual". Forzamos revalidación
        // SIEMPRE (no-cache) apoyándonos en el ETag: el cliente manda If-None-Match y recibe 304 barato si
        // no cambió, o 200 con el precio nuevo en cuanto cambia. Así el cambio es inmediato todo el tiempo.
        if (path.contains("/products/") || path.endsWith("/products")) {
            return "no-cache, must-revalidate";
        }
        // Home (secciones/trending/novedades): al cargar catálogo el admin espera verlo casi al instante.
        // TTL muy corto + ETag → el cambio aparece en ≤5s sin recomputar la home en cada request.
        if (path.contains("/home")) {
            return "public, max-age=5, stale-while-revalidate=30";
        }
        return "public, max-age=30, stale-while-revalidate=120";
    }
}
