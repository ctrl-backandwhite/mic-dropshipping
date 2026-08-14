package com.nexaplatform.dropshipping.infrastructure.security.captcha;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexaplatform.dropshipping.api.dto.out.CaptchaChallengeDtoOut;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Implementación servidor del protocolo <a href="https://altcha.org">ALTCHA</a> (proof-of-work),
 * open-source y sin terceros. Emite retos y verifica soluciones. La idea: cada solicitud protegida
 * obliga al navegador a resolver un pequeño trabajo de cómputo (encontrar el número cuyo hash coincide),
 * lo que encarece el alta/envío MASIVO por bots sin molestar a una persona (lo resuelve el navegador).
 *
 * <p>Complementa —no sustituye— al límite de tasa por IP y a la activación por email ya existentes.
 */
@Slf4j
@Service
public class CaptchaService {

    private static final String ALGORITHM = "SHA-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Clave HMAC del servidor. En local/PRE se genera una aleatoria por arranque si no se define; en
     * producción debe fijarse por entorno para que un reinicio no invalide retos en vuelo (aun así los
     * retos duran segundos, así que el impacto de rotarla es mínimo).
     */
    @Value("${nexadrop.captcha.hmac-key:}")
    private String configuredKey;
    private byte[] hmacKey;

    /** Rango del proof-of-work: a mayor número, más coste para el cliente. 100k resuelve en <1 s. */
    @Value("${nexadrop.captcha.max-number:1000000}")
    private long maxNumber;

    /** Validez del reto. Corto: el cliente lo resuelve en el acto. */
    @Value("${nexadrop.captcha.expiry-seconds:300}")
    private long expirySeconds;

    /** Permite desactivar la verificación (p. ej. en tests o entornos internos). */
    @Value("${nexadrop.captcha.enabled:true}")
    private boolean enabled;

    /**
     * Anti-reutilización: una solución (identificada por su firma) solo vale una vez. Sin esto, un bot
     * resolvería un reto y lo reenviaría mil veces. El TTL coincide con la validez del reto: pasada esta,
     * caduca por fecha y ya no hace falta recordarla.
     */
    private Cache<String, Boolean> consumed;

    @PostConstruct
    void init() {
        this.consumed = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(expirySeconds + 60))
                .maximumSize(100_000)
                .build();
        if (configuredKey != null && !configuredKey.isBlank()) {
            this.hmacKey = configuredKey.getBytes(StandardCharsets.UTF_8);
        } else {
            this.hmacKey = new byte[32];
            secureRandom.nextBytes(this.hmacKey);
            log.info("CAPTCHA: sin nexadrop.captcha.hmac-key; se usa una clave aleatoria por arranque (OK en local/PRE)");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Crea un reto firmado para el cliente. */
    public CaptchaChallengeDtoOut createChallenge() {
        byte[] saltBytes = new byte[12];
        secureRandom.nextBytes(saltBytes);
        long expires = Instant.now().plusSeconds(expirySeconds).getEpochSecond();
        // El 'expires' viaja DENTRO del salt: entra en el hash y en la firma, así que no se puede alterar
        // sin invalidar el reto. En verify se relee de aquí.
        String salt = HexFormat.of().formatHex(saltBytes) + "?expires=" + expires;
        // Con SecureRandom, no con ThreadLocalRandom: el número no es un secreto —el cliente lo encuentra
        // probando, para eso es la prueba de trabajo— pero un generador predecible permitiría adivinarlo
        // sin gastar ese trabajo, que es justo lo único que el reto exige. Cuesta lo mismo y cierra la duda.
        long secretNumber = secureRandom.nextLong(maxNumber + 1);
        String challenge = sha256Hex(salt + secretNumber);
        String signature = hmacHex(challenge);
        return CaptchaChallengeDtoOut.builder().algorithm(ALGORITHM).challenge(challenge)
                .maxnumber(maxNumber).salt(salt).signature(signature).build();
    }

    /**
     * Verifica la solución que envía el cliente (payload ALTCHA en base64). Devuelve {@code true} solo si:
     * el algoritmo es el esperado, {@code SHA-256(salt+number)} reproduce el {@code challenge}, la firma
     * HMAC es la nuestra (reto emitido por el servidor), no ha caducado y no se había usado ya.
     */
    public boolean verify(String payloadBase64) {
        if (!enabled) {
            return true;
        }
        if (payloadBase64 == null || payloadBase64.isBlank()) {
            return false;
        }
        try {
            String json = new String(java.util.Base64.getDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            AltchaPayload p = parse(json);
            if (p == null || !ALGORITHM.equals(p.algorithm)) {
                return false;
            }
            // 1) La firma debe ser la nuestra sobre ESE challenge (reto emitido por el servidor, no fabricado).
            if (!constantTimeEquals(hmacHex(p.challenge), p.signature)) {
                return false;
            }
            // 2) El proof-of-work: el número declarado reproduce el hash del reto.
            if (!constantTimeEquals(sha256Hex(p.salt + p.number), p.challenge)) {
                return false;
            }
            // 3) No caducado (el 'expires' va firmado dentro del salt).
            if (isExpired(p.salt)) {
                return false;
            }
            // 4) Un solo uso: la firma identifica el reto de forma única.
            if (consumed.getIfPresent(p.signature) != null) {
                return false;
            }
            consumed.put(p.signature, Boolean.TRUE);
            return true;
        } catch (RuntimeException e) {
            log.debug("CAPTCHA: payload inválido: {}", e.getMessage());
            return false;
        }
    }

    private boolean isExpired(String salt) {
        int idx = salt.indexOf("?expires=");
        if (idx < 0) {
            return true; // sin marca de caducidad → no lo aceptamos
        }
        try {
            long expires = Long.parseLong(salt.substring(idx + "?expires=".length()));
            return Instant.now().getEpochSecond() > expires;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private record AltchaPayload(String algorithm, String challenge, long number, String salt, String signature) {
    }

    /** Extrae los campos del JSON del payload sin arrastrar dependencias: son valores simples y controlados. */
    private AltchaPayload parse(String json) {
        String algorithm = jsonString(json, "algorithm");
        String challenge = jsonString(json, "challenge");
        String salt = jsonString(json, "salt");
        String signature = jsonString(json, "signature");
        String numberStr = jsonNumber(json, "number");
        if (challenge == null || salt == null || signature == null || numberStr == null) {
            return null;
        }
        return new AltchaPayload(algorithm, challenge, Long.parseLong(numberStr), salt, signature);
    }

    private static String jsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String jsonNumber(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private String hmacHex(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
