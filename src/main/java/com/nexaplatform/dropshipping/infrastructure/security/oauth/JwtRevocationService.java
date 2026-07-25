package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Revocación inmediata de JWT por client_id. Aprovecha que los tokens incluyen
 * el claim `iat` (issued at): registramos un timestamp `revoked_before` por
 * `client_id`. Cualquier JWT con `iat <= revoked_before` se rechaza, sin tocar
 * el token específico — esto cubre todos los tokens activos de ese client al
 * mismo costo O(1) por verificación.
 *
 * Backend preferido: Redis (StringRedisTemplate). Si no está disponible
 * (tests/dev), cae a un ConcurrentHashMap en memoria — pierde estado al
 * reiniciar pero es suficiente para tests.
 *
 * Llamado desde:
 *   - PartnerPlanSyncService.syncForUser     → cambio de plan
 *   - PartnerApiKeysController.revoke        → DELETE api key
 *
 * TTL del entry: igual al TTL máximo del JWT (12h) — pasado ese punto los
 * tokens ya expiraron naturalmente y la entrada se puede limpiar.
 */
@Slf4j
@Service
public class JwtRevocationService {

    private static final String PREFIX = "nx:jwt:revoked-before:";
    private static final String JTI_PREFIX = "nx:jwt:refresh-jti:";
    // Debe ser ≥ la vida del token más largo que protege. El refresh de usuario vive 14 días,
    // así que la marca de revocación debe persistir más que eso; si no, un refresh robado
    // volvería a ser válido al expirar la entrada. (Los tokens de partner viven 12h.)
    private static final Duration TTL = Duration.ofDays(15);

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Long> fallback = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> usedJtis = new ConcurrentHashMap<>();

    public JwtRevocationService(@Autowired(required = false) StringRedisTemplate redis) {
        this.redis = redis;
        if (redis == null) {
            log.warn("Redis not configured — JWT revocation will use in-memory fallback (NOT for production)");
        }
    }

    /** Marca todos los tokens del client como revocados con efecto inmediato. */
    public void revokeAllForClient(String clientId) {
        if (clientId == null || clientId.isBlank())
            return;
        long now = Instant.now().getEpochSecond();
        if (redis != null) {
            redis.opsForValue().set(PREFIX + clientId, Long.toString(now), TTL);
        } else {
            fallback.put(clientId, now);
        }
        log.info("Revoked all tokens for client_id={} at epoch={}", clientId, now);
    }

    /** Atajo cuando varios clients pertenecen al mismo user. */
    public void revokeAllForClients(Collection<String> clientIds) {
        for (String c : clientIds)
            revokeAllForClient(c);
    }

    /**
     * Marca un {@code jti} de refresh token como consumido (rotación). Atómico:
     * devuelve {@code true} solo la PRIMERA vez. Un {@code false} significa que ese
     * refresh ya se canjeó → reuso → posible robo del token (el llamador debe revocar
     * todo el sujeto). TTL ≥ vida del refresh (15 días) para cubrir su ventana completa.
     */
    public boolean consumeRefreshJti(String jti) {
        if (jti == null || jti.isBlank()) {
            return false; // un refresh sin jti se trata como inválido
        }
        if (redis != null) {
            Boolean firstUse = redis.opsForValue().setIfAbsent(JTI_PREFIX + jti, "1", TTL);
            return Boolean.TRUE.equals(firstUse);
        }
        return usedJtis.putIfAbsent(jti, Instant.now().getEpochSecond()) == null;
    }

    /**
     * @param clientId  subject del JWT
     * @param issuedAtEpochSeconds  claim `iat`
     * @return true si el token sigue válido; false si fue revocado masivamente
     */
    public boolean isStillValid(String clientId, long issuedAtEpochSeconds) {
        if (clientId == null)
            return true;
        Long revokedAt;
        if (redis != null) {
            String v = redis.opsForValue().get(PREFIX + clientId);
            revokedAt = v != null ? Long.parseLong(v) : null;
        } else {
            revokedAt = fallback.get(clientId);
        }
        return revokedAt == null || issuedAtEpochSeconds > revokedAt;
    }

    /** Para debugging desde el admin: snapshot de revocaciones activas. */
    public Map<String, Long> snapshot() {
        if (redis != null) {
            Map<String, Long> out = new HashMap<>();
            Set<String> keys = redis.keys(PREFIX + "*");
            if (keys != null) {
                for (Object k : keys) {
                    String key = String.valueOf(k);
                    String v = redis.opsForValue().get(key);
                    if (v != null)
                        out.put(key.substring(PREFIX.length()), Long.parseLong(v));
                }
            }
            return out;
        }
        return new HashMap<>(fallback);
    }
}
