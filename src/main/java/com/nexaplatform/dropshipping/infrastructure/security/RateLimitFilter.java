package com.nexaplatform.dropshipping.infrastructure.security;

import com.nimbusds.jwt.JWTParser;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-client / per-IP rate limiting with RFC-9239-style response headers.
 *
 * Three rule scopes:
 *   - PARTNER  /api/v1/partner/**  → bucket keyed by JWT subject (client_id), tier by URI path.
 *   - PUBLIC   /api/v1/storefront/** + inbound → bucket keyed by client IP, low quota.
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

    // Plan 300k: factory inyectable. Por defecto in-memory (suficiente para
    // local/dev y para una sola instancia). En prod multi-instancia se
    // sustituye por DistributedBucketFactory (Bucket4j sobre Redis) y todas
    // las réplicas comparten el mismo bucket → cuota global consistente.
    @org.springframework.beans.factory.annotation.Autowired
    private com.nexaplatform.dropshipping.infrastructure.security.ratelimit.BucketFactory bucketFactory;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        // El plan se lee del JWT (claim `plan`); por defecto "sandbox" para
        // requests no-partner o JWT sin claim.
        JwtInfo info = bearerInfo(req).orElse(null);
        String plan = info != null ? info.plan() : "sandbox";

        RateRule rule = ruleFor(path, plan);
        if (rule == null) {
            chain.doFilter(req, res);
            return;
        }
        String principal = principal(req, rule, info);
        String key = rule.name + ":" + plan + ":" + principal;
        Bucket bucket = bucketFactory != null
                ? bucketFactory.resolve(key, rule.capacity, rule.period)
                : buckets.computeIfAbsent(key, k -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(rule.capacity)
                                .refillIntervally(rule.capacity, rule.period)
                                .build())
                        .build());

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
            res.getWriter().write(
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\",\"policy\":\""
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
        // Auth abuse-prevention buckets (per-IP)
        if (path.equals("/api/auth/register"))                  return new RateRule("auth.register",   Scope.IP, 5,  Duration.ofHours(1));
        if (path.equals("/api/auth/password-reset/request"))    return new RateRule("auth.reset.req",  Scope.IP, 3,  Duration.ofHours(1));
        if (path.equals("/api/auth/password-reset/confirm"))    return new RateRule("auth.reset.conf", Scope.IP, 5,  Duration.ofHours(1));
        if (path.equals("/login"))                              return new RateRule("auth.login",      Scope.IP, 20, Duration.ofMinutes(1));
        if (path.equals("/oauth2/token"))                       return new RateRule("oauth.token",     Scope.IP, 30, Duration.ofMinutes(1));

        // Partner API per-client buckets — keyed by JWT subject (client_id), CAPACIDAD por plan.
        long partnerCapacity = "paid".equalsIgnoreCase(plan) ? 5L : 1L;
        if (path.startsWith("/api/v1/partner/catalog"))         return new RateRule("partner.catalog.read", Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/partner/orders"))          return new RateRule("partner.orders.write", Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));
        if (path.startsWith("/api/v1/partner/shop"))            return new RateRule("partner.shop.sync",    Scope.PARTNER, partnerCapacity, Duration.ofMinutes(1));

        // Inbound webhooks signed with HMAC — per shop connection (path segment).
        if (path.startsWith("/api/v1/integrations/shops/"))     return new RateRule("inbound.shop",     Scope.PATH_SEG_3, 240, Duration.ofMinutes(1));

        // Public storefront — per IP, generous but bounded.
        if (path.startsWith("/api/v1/storefront/"))             return new RateRule("storefront",       Scope.IP, 60, Duration.ofMinutes(1));

        return null;
    }

    /** Extracts the bucket key part (without the rule name) based on the rule scope. */
    private String principal(HttpServletRequest req, RateRule rule, JwtInfo info) {
        return switch (rule.scope) {
            case PARTNER -> (info != null && info.sub() != null)
                    ? "client:" + info.sub()
                    : "ip:" + clientIp(req);
            case PATH_SEG_3 -> {
                String[] segs = req.getRequestURI().split("/");
                yield segs.length > 5 ? "shop:" + segs[5] : "ip:" + clientIp(req);
            }
            case IP -> "ip:" + clientIp(req);
        };
    }

    /** Plan + subject extraídos del JWT del partner (sin verificación; ya la hizo el resource server). */
    private record JwtInfo(String sub, String plan) {}

    private java.util.Optional<JwtInfo> bearerInfo(HttpServletRequest req) {
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return java.util.Optional.empty();
        try {
            var claims = JWTParser.parse(auth.substring(7)).getJWTClaimsSet();
            String sub = claims.getSubject();
            String plan = claims.getStringClaim("plan");
            return java.util.Optional.of(new JwtInfo(sub, plan != null ? plan : "sandbox"));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    /**
     * Snapshot de políticas para auto-discovery por clientes (GET /api/v1/storefront/rate-limits).
     * Las cuotas partner.* dependen del plan del JWT (`plan` claim).
     */
    public List<Map<String, Object>> policies() {
        return List.of(
                planTiered("partner.catalog.read", "/api/v1/partner/catalog/**", "per client_id (JWT sub)"),
                planTiered("partner.orders.write", "/api/v1/partner/orders/**",  "per client_id (JWT sub)"),
                planTiered("partner.shop.sync",    "/api/v1/partner/shop/**",    "per client_id (JWT sub)"),
                policy("inbound.shop",         "/api/v1/integrations/shops/{id}/**", "per shopConnection id", 240, "1m"),
                policy("storefront",           "/api/v1/storefront/**",      "per IP",   60, "1m"),
                policy("oauth.token",          "/oauth2/token",              "per IP",   30, "1m"),
                policy("auth.login",           "/login",                     "per IP",   20, "1m"),
                policy("auth.register",        "/api/auth/register",         "per IP",   5,  "1h"),
                policy("auth.reset.req",       "/api/auth/password-reset/request", "per IP", 3, "1h"),
                policy("auth.reset.conf",      "/api/auth/password-reset/confirm", "per IP", 5, "1h"));
    }

    private static Map<String, Object> policy(String name, String path, String scope, int capacity, String period) {
        return Map.of("name", name, "path", path, "scope", scope, "capacity", capacity, "period", period);
    }

    /** Política partner con cuota diferenciada por plan (sandbox vs paid). */
    private static Map<String, Object> planTiered(String name, String path, String scope) {
        return Map.of(
                "name", name,
                "path", path,
                "scope", scope,
                "period", "1m",
                "tiers", Map.of(
                        "sandbox", 1,
                        "paid",    5));
    }

    private enum Scope { IP, PARTNER, PATH_SEG_3 }
    private record RateRule(String name, Scope scope, long capacity, Duration period) {}

    /** Test hook to wipe state between tests. */
    public void reset() { buckets.clear(); }

    /** For dependency-free unit reflection. */
    static List<Map<String, Object>> staticPoliciesForDocs() { return Collections.emptyList(); }
}
