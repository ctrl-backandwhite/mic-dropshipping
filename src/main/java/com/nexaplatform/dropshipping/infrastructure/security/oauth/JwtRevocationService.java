package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
    /** Claves por vuelta de SCAN: suficientes para no encadenar mil viajes, pocas para no bloquear. */
    private static final int SCAN_BATCH = 200;
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

    /**
     * Marca todos los tokens del client como revocados con efecto inmediato.
     *
     * <p>Si Redis no responde se anota en el registro en memoria, igual que hace la lectura. La razón
     * es la misma que allí, y aquí pesa incluso más: quien revoca una credencial suele estar
     * respondiendo a una filtración. Dejar que un Redis caído devuelva un error significaría que la
     * clave comprometida NO se revoca —la transacción entera se deshace, incluido el borrado— y que la
     * persona se queda mirando un 500 sin saber si su clave sigue viva. Es exactamente el momento en
     * que el sistema no se puede permitir fallar.
     *
     * <p>Lo que se pierde con el respaldo en memoria es que la revocación no llegue a las demás
     * réplicas mientras Redis esté caído. Pero el borrado en la base de datos SÍ ocurre, así que la
     * credencial deja de servir para pedir tokens nuevos en todas ellas; lo único que sobrevive es
     * algún token ya emitido, hasta que caduque. Se avisa en el registro para que no pase inadvertido.
     */
    public void revokeAllForClient(String clientId) {
        if (clientId == null || clientId.isBlank())
            return;
        long now = Instant.now().getEpochSecond();
        if (redis == null) {
            fallback.put(clientId, now);
        } else {
            try {
                redis.opsForValue().set(PREFIX + clientId, Long.toString(now), TTL);
            } catch (RuntimeException e) {
                fallback.put(clientId, now);
                log.warn("::> [REVOCACION] Redis no responde al revocar client_id={} ({}). La revocación "
                        + "queda en memoria: la credencial YA está borrada, pero un token vivo podría "
                        + "seguir siéndolo en otras réplicas hasta que caduque.", clientId,
                        e.getClass().getSimpleName());
            }
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
        Long revokedAt = leerRevocacion(clientId);
        return revokedAt == null || issuedAtEpochSeconds > revokedAt;
    }

    /**
     * Momento de la revocación masiva de ese cliente, o {@code null} si no la hay.
     *
     * <p>Si Redis está configurado pero no responde, se cae al registro en memoria
     * en vez de propagar el fallo. El motivo: esto se ejecuta al validar CADA
     * token, así que un Redis caído dejaría a todo el mundo fuera de la
     * aplicación —incluida la parte que no usa Redis para nada—. Es preferible
     * perder temporalmente la revocación masiva, que es una medida excepcional,
     * antes que tumbar la autenticación entera.
     *
     * <p>El registro en memoria no se comparte entre réplicas, así que mientras
     * Redis esté caído una revocación podría no llegar a todos los pods. Se avisa
     * en el registro para que no pase inadvertido.
     */
    private Long leerRevocacion(String clientId) {
        if (redis == null) {
            return fallback.get(clientId);
        }
        try {
            String v = redis.opsForValue().get(PREFIX + clientId);
            return v != null ? Long.parseLong(v) : null;
        } catch (RuntimeException e) {
            log.warn("::> [REVOCACION] Redis no responde ({}), se usa el registro en memoria. "
                    + "Las revocaciones masivas pueden no llegar a todas las réplicas mientras dure.",
                    e.getClass().getSimpleName());
            return fallback.get(clientId);
        }
    }

    /**
     * Para debugging desde el admin: snapshot de revocaciones activas.
     *
     * <p>Se recorre con SCAN y no con KEYS: KEYS bloquea a Redis mientras recorre TODO el keyspace, así
     * que abrir esta pantalla de diagnóstico en producción congelaba de paso la sesión de cada usuario
     * que hubiera en ese momento. SCAN va por trozos y deja respirar al servidor entre ellos.
     */
    public Map<String, Long> snapshot() {
        if (redis == null) {
            return new HashMap<>(fallback);
        }
        Map<String, Long> out = new HashMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(PREFIX + "*").count(SCAN_BATCH).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String v = redis.opsForValue().get(key);
                if (v != null) {
                    out.put(key.substring(PREFIX.length()), Long.parseLong(v));
                }
            }
        }
        return out;
    }
}
