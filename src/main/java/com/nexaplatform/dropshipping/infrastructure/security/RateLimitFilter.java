package com.nexaplatform.dropshipping.infrastructure.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nexaplatform.dropshipping.infrastructure.security.ratelimit.BucketFactory;
import com.nimbusds.jwt.JWTParser;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-client / per-IP rate limiting with RFC-9239-style response headers.
 *
 * Three rule scopes:
 *   - PARTNER  /api/v1/partner/**  → bucket keyed by JWT subject (client_id), tier by URI path.
 *   - PUBLIC   /api/v1/rate-limits,/api/v1/invoices + inbound → bucket keyed by client IP, low quota.
 *   - AUTH     /api/auth/*, /oauth2/token, /login → bucket keyed by client IP, abuse-prevention quota.
 *
 * Every response carries:
 *   RateLimit-Limit:      <capacity>
 *   RateLimit-Remaining:  <tokens_left>
 *   RateLimit-Reset:      <seconds_until_full_refill>
 *   X-RateLimit-Policy:   <rule name>  (vendor-specific, useful for debugging)
 *
 * On 429:
 *   Retry-After: <seconds>
 *
 * Buckets are stored in-memory (single instance). For multi-instance prod, swap the
 * Bucket factory for the Redis-distributed Bucket4j variant.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String PER_CLIENT_ID_JWT_SUB = "per client_id (JWT sub)";
    private static final String SANDBOX = "sandbox";
    private static final String PER_IP = "per IP";

    // Plan 300k: factory inyectable. Por defecto in-memory (suficiente para
    // local/dev y para una sola instancia). En prod multi-instancia se
    // sustituye por DistributedBucketFactory (Bucket4j sobre Redis) y todas
    // las réplicas comparten el mismo bucket → cuota global consistente.
    private final BucketFactory bucketFactory;

    // Nº de proxies de confianza por delante (LB/edge). La IP real del cliente es la que
    // añade el proxy de confianza al final de X-Forwarded-For; los valores que el cliente
    // pueda inyectar quedan a la izquierda. 1 = un único proxy (típico Railway/Nginx).
    @Value("${nexadrop.security.trusted-proxy-count:1}")
    private int trustedProxyCount;

    /**
     * Interruptor del límite. Por defecto ENCENDIDO: apagarlo deja la API sin defensa contra abuso, así
     * que solo debe apagarse en el perfil de pruebas, donde una batería de integración dispara cientos de
     * peticiones seguidas desde la misma IP y el límite las corta con 429 sin que nada esté mal.
     *
     * <p>Se inicializa a {@code true} en la propia declaración y no solo por configuración: un
     * {@code boolean} sin inicializar vale {@code false}, así que quien construya el filtro sin Spring
     * —los tests unitarios lo hacen— se quedaría sin límite y sin enterarse. El fallo por defecto tiene
     * que ser hacia el lado seguro.
     */
    @Value("${nexadrop.ratelimit.enabled:true}")
    private boolean rateLimitEnabled = true;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Inyección por constructor (java:S6813). Va anotado porque hay un segundo constructor: con más de uno
     * y ninguno marcado, Spring elegiría el vacío y el filtro se quedaría sin la factoría compartida.
     */
    @Autowired
    public RateLimitFilter(BucketFactory bucketFactory) {
        this.bucketFactory = bucketFactory;
    }

    /** Sin factoría: cada instancia lleva sus propios cubos en memoria. Lo usan las pruebas del filtro. */
    public RateLimitFilter() {
        this(null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!rateLimitEnabled) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI();
        // El plan se lee del JWT (claim `plan`); por defecto SANDBOX para
        // requests no-partner o JWT sin claim.
        JwtInfo info = bearerInfo(req).orElse(null);
        String plan = info != null ? info.plan() : SANDBOX;

        RateRule rule = ruleFor(path, plan);
        if (rule == null) {
            chain.doFilter(req, res);
            return;
        }
        String principal = principal(req, rule, info);
        String key = rule.name + ":" + plan + ":" + principal;
        Bucket bucket = bucketFactory != null
                ? bucketFactory.resolve(key, rule.capacity, rule.period)
                : buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(Bandwidth.builder()
                        .capacity(rule.capacity).refillIntervally(rule.capacity, rule.period).build()).build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long resetSeconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        res.setHeader("RateLimit-Limit", String.valueOf(rule.capacity));
        res.setHeader("RateLimit-Remaining", String.valueOf(Math.max(0, probe.getRemainingTokens())));
        res.setHeader("RateLimit-Reset", String.valueOf(resetSeconds));
        res.setHeader("X-RateLimit-Policy", rule.name);

        if (probe.isConsumed()) {
            chain.doFilter(req, res);
        } else {
            long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            res.setStatus(429);
            res.setHeader("Retry-After", String.valueOf(retryAfter));
            res.setContentType("application/json");
            res.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\",\"policy\":\""
                    + rule.name + "\",\"retryAfterSeconds\":" + retryAfter + "}");
        }
    }

    /**
     * First-match wins. Order from most specific to most generic.
     *
     * Partner API quotas son por PLAN:
     *   - sandbox/free → 1 req/min
     *   - paid         → 5 req/min
     *
     * El plan viene del claim `plan` del JWT del partner.
     */
    private RateRule ruleFor(String path, String plan) {
        // El orden importa: primero las reglas de AUTH (las más restrictivas y las que frenan el abuso),
        // luego las de partner por client_id y por último las públicas por IP, que son las más generosas.
        // Si se invirtiera, una ruta de auth caería en el cubo público de 100/min y quedaría sin freno.
        RateRule auth = authRule(path);
        if (auth != null) {
            return auth;
        }
        RateRule partner = partnerRule(path, plan);
        if (partner != null) {
            return partner;
        }
        return publicRule(path);
    }

    /** Cubos anti-abuso de autenticación, todos por IP. */
    private RateRule authRule(String path) {
        // El login real es /api/auth/login (no /login): sin esta regla la fuerza bruta/credential-stuffing
        // pasaba sin freno.
        if (path.equals("/api/auth/login"))
            return new RateRule("auth.login.api", Scope.IP, 10, Duration.ofMinutes(1));
        if (path.equals("/api/auth/refresh"))
            return new RateRule("auth.refresh", Scope.IP, 30, Duration.ofMinutes(1));
        if (path.equals("/api/auth/register"))
            return new RateRule("auth.register", Scope.IP, 5, Duration.ofHours(1));
        // Reenvío del email de activación: sin freno se podía bombardear el buzón de una víctima (el CAPTCHA
        // PoW es barato). 5/hora por IP, en línea con el registro.
        if (path.equals("/api/auth/activate/resend"))
            return new RateRule("auth.activate.resend", Scope.IP, 5, Duration.ofHours(1));
        if (path.equals("/api/auth/password-reset/request"))
            return new RateRule("auth.reset.req", Scope.IP, 20, Duration.ofHours(1));
        if (path.equals("/api/auth/password-reset/confirm"))
            return new RateRule("auth.reset.conf", Scope.IP, 5, Duration.ofHours(1));
        if (path.equals("/login"))
            return new RateRule("auth.login", Scope.IP, 20, Duration.ofMinutes(1));
        if (path.equals("/oauth2/token"))
            return new RateRule("oauth.token", Scope.IP, 30, Duration.ofMinutes(1));
        return null;
    }

    /** Partner API per-client buckets — keyed by JWT subject (client_id), CAPACIDAD por plan. */
    private RateRule partnerRule(String path, String plan) {
        long partnerCapacity = "paid".equalsIgnoreCase(plan) ? 5L : 1L;
        if (path.startsWith("/api/v1/partner/catalog"))
            return new RateRule("partner.catalog.read", Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/partner/orders"))
            return new RateRule("partner.orders.write", Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/partner/shop"))
            return new RateRule("partner.shop.sync", Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));
        return null;
    }

    /** Webhooks entrantes y API pública (SPA y desarrolladores): cubos por IP. */
    private RateRule publicRule(String path) {
        // Inbound webhooks signed with HMAC — per shop connection (path segment).
        if (path.startsWith("/api/v1/integrations/shops/"))
            return new RateRule("inbound.shop", Scope.PATH_SEG_3, 240, Duration.ofMinutes(1));

        // Emisión de challenges CAPTCHA: sin freno, un bot puede pedir retos sin límite (el PoW es barato).
        if (path.equals("/api/captcha/challenge"))
            return new RateRule("captcha.challenge", Scope.IP, 60, Duration.ofMinutes(1));

        // Public versioned API (for developers) — per IP, generous but bounded.
        if (path.startsWith("/api/v1/rate-limits") || path.startsWith("/api/v1/invoices"))
            return new RateRule("storefront", Scope.IP, 60, Duration.ofMinutes(1));

        // Public API used by the web SPA (catalog browse + navegación) — per IP. Anti-clonado: frena el
        // volcado masivo del catálogo/fichas sin molestar a un humano (una página son ~3-5 llamadas).
        if (path.startsWith("/api/catalog/") || path.startsWith("/api/search") || path.startsWith("/api/shipping/")
                || path.startsWith("/api/currency/") || path.startsWith("/api/languages")
                || path.startsWith("/api/warehouses") || path.startsWith("/api/academy/")
                || path.startsWith("/api/mentors") || path.startsWith("/api/pod/")
                || path.startsWith("/api/billing/") || path.startsWith("/api/contact")
                || path.startsWith("/api/newsletter/") || path.startsWith("/api/affiliate/"))
            return new RateRule("storefront.web", Scope.IP, 100, Duration.ofMinutes(1));

        return null;
    }

    /** Extracts the bucket key part (without the rule name) based on the rule scope. */
    private String principal(HttpServletRequest req, RateRule rule, JwtInfo info) {
        return switch (rule.scope) {
            case PARTNER -> (info != null && info.sub() != null) ? "client:" + info.sub() : "ip:" + clientIp(req);
            case PATH_SEG_3 -> {
                String[] segs = req.getRequestURI().split("/");
                yield segs.length > 5 ? "shop:" + segs[5] : "ip:" + clientIp(req);
            }
            case IP -> "ip:" + clientIp(req);
        };
    }

    /** Plan + subject extraídos del JWT del partner (sin verificación; ya la hizo el resource server). */
    private record JwtInfo(String sub, String plan) {
    }

    private Optional<JwtInfo> bearerInfo(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer "))
            return Optional.empty();
        try {
            JWTClaimsSet claims = JWTParser.parse(auth.substring(7)).getJWTClaimsSet();
            String sub = claims.getSubject();
            String plan = claims.getStringClaim("plan");
            return Optional.of(new JwtInfo(sub, plan != null ? plan : SANDBOX));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String clientIp(HttpServletRequest req) {
        // Detrás de Cloudflare (producción: api.nx036.com), CF-Connecting-IP es la IP REAL del cliente y
        // Cloudflare SOBRESCRIBE cualquier valor que mande el cliente → no es falsificable por tráfico que
        // pasa por CF. Es la fuente autoritativa cuando existe, y evita el bypass del rate-limit por
        // X-Forwarded-For rotado. (Requiere además bloquear el acceso DIRECTO al origen Railway para que
        // nadie salte Cloudflare; ver nota de despliegue.)
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank())
            return req.getRemoteAddr();
        String[] parts = xff.split(",");
        // Una cabecera de solo comas (",", ",,,") deja el array VACÍO: split descarta los trozos vacíos
        // finales. Sin este corte, el acceso por índice lanzaba ArrayIndexOutOfBoundsException dentro del
        // filtro que limita las peticiones. Hoy Tomcat rechaza antes esa cabecera, pero depender de eso
        // deja el fallo a merced de la configuración de proxies.
        if (parts.length == 0) {
            return req.getRemoteAddr();
        }
        // Tomamos la IP que añadió el proxy de confianza (a `trustedProxyCount` desde el final),
        // NO la primera, que el cliente puede falsificar para evadir el rate limit por IP.
        int idx = parts.length - Math.max(1, trustedProxyCount);
        if (idx < 0)
            idx = 0;
        String ip = parts[idx].trim();
        return ip.isEmpty() ? req.getRemoteAddr() : ip;
    }

    /**
     * Snapshot de políticas para auto-discovery por clientes (GET /api/v1/rate-limits).
     * Las cuotas partner.* dependen del plan del JWT (`plan` claim).
     */
    public List<Map<String, Object>> policies() {
        return List.of(planTiered("partner.catalog.read", "/api/v1/partner/catalog/**", PER_CLIENT_ID_JWT_SUB),
                planTiered("partner.orders.write", "/api/v1/partner/orders/**", PER_CLIENT_ID_JWT_SUB),
                planTiered("partner.shop.sync", "/api/v1/partner/shop/**", PER_CLIENT_ID_JWT_SUB),
                policy("inbound.shop", "/api/v1/integrations/shops/{id}/**", "per shopConnection id", 240, "1m"),
                policy("storefront", "/api/v1/rate-limits, /api/v1/invoices", PER_IP, 60, "1m"),
                policy("storefront.web", "/api/catalog/**, /api/search, ... (navegación pública)", PER_IP, 100, "1m"),
                policy("oauth.token", "/oauth2/token", PER_IP, 30, "1m"),
                policy("auth.login", "/login", PER_IP, 20, "1m"),
                policy("auth.login.api", "/api/auth/login", PER_IP, 10, "1m"),
                policy("auth.refresh", "/api/auth/refresh", PER_IP, 30, "1m"),
                policy("auth.register", "/api/auth/register", PER_IP, 5, "1h"),
                policy("auth.reset.req", "/api/auth/password-reset/request", PER_IP, 20, "1h"),
                policy("auth.reset.conf", "/api/auth/password-reset/confirm", PER_IP, 5, "1h"));
    }

    private static Map<String, Object> policy(String name, String path, String scope, int capacity, String period) {
        return Map.of("name", name, "path", path, "scope", scope, "capacity", capacity, "period", period);
    }

    /** Política partner con cuota diferenciada por plan (sandbox vs paid). */
    private static Map<String, Object> planTiered(String name, String path, String scope) {
        return Map.of("name", name, "path", path, "scope", scope, "period", "1m", "tiers",
                Map.of(SANDBOX, 1, "paid", 5));
    }

    private enum Scope {
        IP, PARTNER, PATH_SEG_3
    }

    private record RateRule(String name, Scope scope, long capacity, Duration period) {
    }

    /**
     * Gancho de pruebas: deja la cuota a cero entre casos.
     *
     * <p>Antes vaciaba SOLO el mapa local, que en la aplicación real no se rellena nunca: en cuanto hay
     * una {@link BucketFactory} inyectada —siempre, salvo en las pruebas unitarias de este filtro, que
     * usan el constructor sin argumentos— los cubos viven dentro de la factoría. El método prometía
     * «wipe state between tests» y no vaciaba nada, así que la cuota que gastaba un caso se arrastraba
     * al siguiente y aparecían 429 donde el caso medía 401/403.
     */
    public void reset() {
        buckets.clear();
        if (bucketFactory != null) {
            bucketFactory.clear();
        }
    }

    /** For dependency-free unit reflection. */
    static List<Map<String, Object>> staticPoliciesForDocs() {
        return Collections.emptyList();
    }
}
