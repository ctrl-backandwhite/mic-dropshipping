package com.nexaplatform.dropshipping.infrastructure.security.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM authenticated encryption for secrets at rest (shop access tokens,
 * webhook secrets, partner-side credentials).
 *
 * Wire format (Base64 of the concatenated bytes):
 *   [ 1 byte  version = 0x01 ]
 *   [ 1 byte  keyId        ]  // index into the configured KEK list, for rotation
 *   [ 12 bytes IV (nonce)  ]  // random per record
 *   [ N bytes ciphertext + 16 bytes auth tag ]
 *
 * Prefix on the stored string: `gcm:` (so we can distinguish from the legacy
 * `enc:` Base64 placeholder during migration).
 *
 * Configuration:
 *   nexadrop.crypto.token-keks = comma-separated list of Base64-encoded 32-byte keys.
 *     The FIRST entry is the active KEK (used for new encryptions). Older entries
 *     still decrypt records they previously encrypted (rotation without re-encrypt).
 *
 *   Generate a key:
 *     openssl rand -base64 32
 *
 * If the property is empty, a one-shot ephemeral key is generated and a WARN is
 * logged — that is acceptable for local dev but MUST NOT be used in production
 * (restarts invalidate all stored secrets).
 */
@Slf4j
@Service
public class TokenCryptoService {

    private static final String PREFIX = "gcm:";
    private static final byte VERSION = 0x01;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEK_LEN = 32; // AES-256

    private final SecureRandom random = new SecureRandom();
    private final String configuredKeys;
    private SecretKey[] keks;

    public TokenCryptoService(@Value("${nexadrop.crypto.token-keks:}") String configuredKeys) {
        this.configuredKeys = configuredKeys;
    }

    @PostConstruct
    void init() {
        if (configuredKeys == null || configuredKeys.isBlank()) {
            log.warn("nexadrop.crypto.token-keks is empty — generating an ephemeral KEK. "
                    + "Set the property (Base64 of 32 random bytes) before deploying to production.");
            byte[] raw = new byte[KEK_LEN];
            random.nextBytes(raw);
            keks = new SecretKey[]{new SecretKeySpec(raw, "AES")};
            return;
        }
        String[] parts = configuredKeys.split(",");
        keks = new SecretKey[parts.length];
        for (int i = 0; i < parts.length; i++) {
            byte[] raw = Base64.getDecoder().decode(parts[i].trim());
            if (raw.length != KEK_LEN) {
                throw new IllegalStateException(
                        "nexadrop.crypto.token-keks[" + i + "] must decode to exactly 32 bytes, got " + raw.length);
            }
            keks[i] = new SecretKeySpec(raw, "AES");
        }
    }

    /** Returns the encrypted token with the `gcm:` prefix, ready to persist. */
    public String encrypt(String plaintext) {
        if (plaintext == null)
            return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keks[0], new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[2 + IV_LEN + ct.length];
            out[0] = VERSION;
            out[1] = (byte) 0; // active key id = 0
            System.arraycopy(iv, 0, out, 2, IV_LEN);
            System.arraycopy(ct, 0, out, 2 + IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new CryptoException("Failed to encrypt token", e);
        }
    }

    /**
     * Decrypts a stored token. Accepts:
     *   - `gcm:...` modern records
     *   - `enc:...` legacy Base64 placeholder (auto-migrate-on-read; caller decides whether to re-save)
     *   - null / empty → returns null
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty())
            return null;
        if (stored.startsWith("enc:")) {
            // Legacy Base64-only placeholder. Decode and return; caller should re-encrypt and persist.
            return new String(Base64.getDecoder().decode(stored.substring(4)), StandardCharsets.UTF_8);
        }
        if (!stored.startsWith(PREFIX)) {
            // Unrecognised — assume already plaintext (defensive; do not crash).
            return stored;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length < 2 + IV_LEN + 16)
                throw new CryptoException("Ciphertext too short", null);
            byte version = raw[0];
            int keyId = raw[1] & 0xFF;
            if (version != VERSION)
                throw new CryptoException("Unsupported crypto version: " + version, null);
            if (keyId >= keks.length)
                throw new CryptoException(
                        "Unknown keyId " + keyId
                                + " — was the record encrypted with a key no longer in nexadrop.crypto.token-keks?",
                        null);

            byte[] iv = new byte[IV_LEN];
            System.arraycopy(raw, 2, iv, 0, IV_LEN);
            byte[] ct = new byte[raw.length - 2 - IV_LEN];
            System.arraycopy(raw, 2 + IV_LEN, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keks[keyId], new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Failed to decrypt token", e);
        }
    }

    /** True if the stored value uses the current (modern) format. */
    public boolean isModern(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
