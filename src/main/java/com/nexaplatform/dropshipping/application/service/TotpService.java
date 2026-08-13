package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.TotpSecretEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.TotpSecretRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * DROP-436: TOTP RFC 6238 + códigos de respaldo.
 *
 * Flujo:
 *   1. setup(userId) → genera secret aleatorio, lo guarda cifrado con AES-GCM,
 *      devuelve el secret en Base32 y la URL `otpauth://` para que el cliente
 *      muestre un QR.
 *   2. verifyAndEnable(userId, otp) → valida el OTP de la app autenticadora,
 *      activa 2FA y devuelve 10 backup codes únicos (mostrados una sola vez).
 *   3. verifyOtp(userId, otp) → check estándar (login second factor).
 *   4. consumeBackupCode(userId, code) → si OTP no disponible, acepta un
 *      backup code; cada uno se invalida al usarse.
 *   5. disable(userId) → borra el registro.
 *   6. regenerateBackupCodes(userId) → nuevo set de 10, invalida los previos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TotpService {

    /**
     * Algoritmo del estándar TOTP (RFC 6238). No es una elección discutible: Google Authenticator, Authy
     * y el resto de aplicaciones solo interoperan con HMAC-SHA1, y además se usa como HMAC con clave
     * secreta, no como hash desnudo.
     */
    private static final String ALG = "HmacSHA1"; // NOSONAR java:S4790 — exigido por el estándar TOTP
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int WINDOW = 1; // tolera ±30s de drift
    private static final int SECRET_BYTES = 20;
    private static final int BACKUP_COUNT = 10;
    private static final int BACKUP_CHARS = 10;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final TotpSecretRepository repo;
    private final UserRepository userRepo;
    private final TokenCryptoService crypto;
    private final ObjectMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom rng = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    public record SetupResult(String base32Secret, String otpauthUrl) {
    }

    public record EnableResult(List<String> backupCodes) {
    }

    /**
     * Disable 2FA after re-checking the user's account password. Moves the
     * password verification previously living in the controller into the service.
     */
    @Transactional
    public void disableWithPassword(UUID userId, String rawPassword) {
        UserEntity user = userRepo.findById(userId).orElseThrow(() -> new BusinessException("User not found"));
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException("Invalid password");
        }
        deleteSecret(userId);
    }

    /**
     * Genera un secret nuevo (o reusa el existente si aún no está activo) y
     * devuelve los datos para mostrar el QR. NO activa 2FA — eso pasa en verifyAndEnable.
     */
    @Transactional
    public SetupResult setup(UUID userId) {
        UserEntity user = userRepo.findById(userId).orElseThrow(() -> new NotFoundException("User"));
        TotpSecretEntity existing = repo.findById(userId).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw new BusinessException("2FA is already enabled. Disable it first to re-setup.");
        }
        byte[] raw = new byte[SECRET_BYTES];
        rng.nextBytes(raw);
        String b32 = base32(raw);

        TotpSecretEntity rec = existing != null ? existing : TotpSecretEntity.builder().userId(userId).build();
        rec.setSecretEnc(crypto.encrypt(b32));
        rec.setEnabled(false);
        rec.setUpdatedAt(Instant.now());
        repo.save(rec);

        String issuer = "NX036";
        String label = issuer + ":" + user.getEmail();
        String url = "otpauth://totp/" + urlEnc(label) + "?secret=" + b32 + "&issuer=" + urlEnc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
        return new SetupResult(b32, url);
    }

    /** Verifica el OTP introducido por el usuario contra el secret pendiente y activa 2FA. */
    @Transactional
    public EnableResult verifyAndEnable(UUID userId, String otp) {
        TotpSecretEntity rec = repo.findById(userId).orElseThrow(() -> new NotFoundException("2FA not initialized"));
        if (rec.isEnabled())
            throw new BusinessException("2FA already enabled");
        String secret = crypto.decrypt(rec.getSecretEnc());
        if (!verifyAtTime(secret, otp, System.currentTimeMillis() / 1000)) {
            throw new BusinessException("Invalid OTP");
        }
        // Generar backup codes y guardar hashes
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < BACKUP_COUNT; i++) {
            String c = randomBackupCode();
            codes.add(c);
            hashes.add(encoder.encode(c));
        }
        try {
            rec.setRecoveryCodesHash(mapper.writeValueAsString(hashes));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        rec.setEnabled(true);
        rec.setLastUsedAt(Instant.now());
        rec.setUpdatedAt(Instant.now());
        repo.save(rec);
        return new EnableResult(codes);
    }

    /** Verifica un OTP contra el secret ya activo (uso en login). Rechaza la reutilización (replay). */
    @Transactional
    public boolean verifyOtp(UUID userId, String otp) {
        TotpSecretEntity rec = repo.findById(userId).orElse(null);
        if (rec == null || !rec.isEnabled())
            return false;
        String secret = crypto.decrypt(rec.getSecretEnc());
        long matched = matchingCounter(secret, otp, System.currentTimeMillis() / 1000);
        if (matched < 0) {
            return false;
        }
        // Anti-replay: el contador del último OTP consumido se guarda en lastUsedAt (inicio del step). Un
        // código de un step YA usado (o anterior) se rechaza, aunque siga dentro de su ventana de validez.
        long lastCounter = rec.getLastUsedAt() != null ? rec.getLastUsedAt().getEpochSecond() / PERIOD_SECONDS : -1L;
        if (matched <= lastCounter) {
            return false;
        }
        rec.setLastUsedAt(Instant.ofEpochSecond(matched * PERIOD_SECONDS));
        rec.setUpdatedAt(Instant.now());
        repo.save(rec);
        return true;
    }

    /** Acepta un backup code (single-use). Invalida el code al consumirse. */
    @Transactional
    public boolean consumeBackupCode(UUID userId, String code) {
        TotpSecretEntity rec = repo.findById(userId).orElse(null);
        if (rec == null || !rec.isEnabled() || rec.getRecoveryCodesHash() == null)
            return false;
        try {
            @SuppressWarnings("unchecked")
            List<String> hashes = mapper.readValue(rec.getRecoveryCodesHash(), List.class);
            for (int i = 0; i < hashes.size(); i++) {
                String h = hashes.get(i);
                if (h != null && encoder.matches(code, h)) {
                    hashes.set(i, null); // consume
                    rec.setRecoveryCodesHash(mapper.writeValueAsString(hashes));
                    rec.setLastUsedAt(Instant.now());
                    repo.save(rec);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Backup code check failed for {}: {}", userId, e.getMessage());
        }
        return false;
    }

    @Transactional
    public void disable(UUID userId) {
        deleteSecret(userId);
    }

    /**
     * Borrado del secreto SIN anotar: es al que llama {@link #disableWithPassword}, que ya está dentro de
     * su transacción. Una llamada dentro de la misma instancia no pasa por el proxy de Spring, de modo que
     * el {@code @Transactional} del método público no se aplicaría a la llamada interna.
     */
    private void deleteSecret(UUID userId) {
        repo.deleteById(userId);
    }

    @Transactional
    public List<String> regenerateBackupCodes(UUID userId) {
        TotpSecretEntity rec = repo.findById(userId).orElseThrow(() -> new NotFoundException("2FA not enabled"));
        if (!rec.isEnabled())
            throw new BusinessException("Enable 2FA first");
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < BACKUP_COUNT; i++) {
            String c = randomBackupCode();
            codes.add(c);
            hashes.add(encoder.encode(c));
        }
        try {
            rec.setRecoveryCodesHash(mapper.writeValueAsString(hashes));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        rec.setUpdatedAt(Instant.now());
        repo.save(rec);
        return codes;
    }

    public boolean isEnabled(UUID userId) {
        return repo.findById(userId).map(TotpSecretEntity::isEnabled).orElse(false);
    }

    /* ============================ Internals ============================ */

    private boolean verifyAtTime(String base32Secret, String otp, long epochSeconds) {
        return matchingCounter(base32Secret, otp, epochSeconds) >= 0;
    }

    /**
     * Devuelve el contador de time-step (epoch/30) para el que casa el OTP dentro de la ventana de drift,
     * o -1 si no casa ninguno. Se expone el contador para poder RECHAZAR la reutilización del mismo código
     * (replay): un OTP TOTP es válido durante toda su ventana (±30s), y sin esto se podía reenviar el mismo
     * código varias veces (RFC 6238 §5.2 pide invalidar el paso ya consumido).
     */
    private long matchingCounter(String base32Secret, String otp, long epochSeconds) {
        if (otp == null || otp.length() != DIGITS)
            return -1L;
        byte[] key = base32Decode(base32Secret);
        long counter = epochSeconds / PERIOD_SECONDS;
        // Window: cuenta actual + WINDOW pasadas/futuras (drift)
        for (int w = -WINDOW; w <= WINDOW; w++) {
            if (otp.equals(generate(key, counter + w)))
                return counter + w;
        }
        return -1L;
    }

    private String generate(byte[] key, long counter) {
        try {
            byte[] data = new byte[8];
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (counter & 0xFF);
                counter >>= 8;
            }
            Mac mac = Mac.getInstance(ALG); // NOSONAR java:S4790 — HMAC-SHA1 lo exige el estándar TOTP (RFC 6238)
            mac.init(new SecretKeySpec(key, ALG));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int code = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            code = code % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", code);
        } catch (Exception e) {
            throw new RuntimeException("TOTP generate failed", e);
        }
    }

    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((value >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0)
            sb.append(BASE32.charAt((value << (5 - bits)) & 0x1F));
        return sb.toString();
    }

    private static byte[] base32Decode(String s) {
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bits = 0;
        int value = 0;
        for (char c : s.toCharArray()) {
            int idx = BASE32.indexOf(c);
            if (idx < 0)
                continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String randomBackupCode() {
        // Formato "XXXXX-XXXXX" total 11 chars; ~6 bytes de entropía base32
        byte[] raw = new byte[6];
        rng.nextBytes(raw);
        String b32 = base32(raw).substring(0, BACKUP_CHARS);
        return b32.substring(0, 5) + "-" + b32.substring(5);
    }

    private static String urlEnc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Inválido sólo para evitar warning del import unused en algunos builds. */
    @SuppressWarnings("unused")
    private String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
