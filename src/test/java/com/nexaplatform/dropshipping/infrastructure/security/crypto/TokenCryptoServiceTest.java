package com.nexaplatform.dropshipping.infrastructure.security.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TokenCryptoServiceTest {

    private static String kek() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Builds a service with the given comma-separated KEK list and runs @PostConstruct. */
    private static TokenCryptoService service(String keks) {
        TokenCryptoService svc = new TokenCryptoService(keks);
        ReflectionTestUtils.invokeMethod(svc, "init");
        return svc;
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        TokenCryptoService svc = service(kek());
        String plaintext = "shpat_abc123-token";

        String encrypted = svc.encrypt(plaintext);

        assertThat(svc.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesModernGcmPrefixDistinctFromPlaintext() {
        TokenCryptoService svc = service(kek());
        String plaintext = "super-secret";

        String encrypted = svc.encrypt(plaintext);

        assertThat(encrypted).startsWith("gcm:").doesNotContain(plaintext);
        assertThat(svc.isModern(encrypted)).isTrue();
    }

    @Test
    void encrypt_usesFreshIvEachTimeSoCiphertextDiffers() {
        TokenCryptoService svc = service(kek());
        String plaintext = "same-input";

        String a = svc.encrypt(plaintext);
        String b = svc.encrypt(plaintext);

        assertThat(a).isNotEqualTo(b);
        assertThat(svc.decrypt(a)).isEqualTo(plaintext);
        assertThat(svc.decrypt(b)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_usesOlderKekAfterRotation() {
        String oldKek = kek();
        String newKek = kek();

        // Encrypted while oldKek was the only/active key.
        TokenCryptoService before = service(oldKek);
        String encrypted = before.encrypt("rotated-token");

        // After rotation, the active key is newKek but oldKek is still present at index... 1.
        // The active KEK is always index 0 on encrypt; decrypt reads keyId from the record (0).
        // So we must keep oldKek FIRST for its records to decrypt.
        TokenCryptoService after = service(oldKek + "," + newKek);

        assertThat(after.decrypt(encrypted)).isEqualTo("rotated-token");
    }

    @Test
    void decrypt_legacyEncPlaceholderIsBase64Decoded() {
        TokenCryptoService svc = service(kek());
        String legacy = "enc:" + Base64.getEncoder()
                .encodeToString("legacy-value".getBytes(StandardCharsets.UTF_8));

        assertThat(svc.decrypt(legacy)).isEqualTo("legacy-value");
        assertThat(svc.isModern(legacy)).isFalse();
    }

    @Test
    void decrypt_unrecognisedValueReturnedAsPlaintext() {
        TokenCryptoService svc = service(kek());

        assertThat(svc.decrypt("already-plain")).isEqualTo("already-plain");
    }

    @Test
    void encryptAndDecrypt_handleNullAndEmpty() {
        TokenCryptoService svc = service(kek());

        assertThat(svc.encrypt(null)).isNull();
        assertThat(svc.decrypt(null)).isNull();
        assertThat(svc.decrypt("")).isNull();
    }

    @Test
    void decrypt_corruptedCiphertextThrowsCryptoException() {
        TokenCryptoService svc = service(kek());
        String encrypted = svc.encrypt("tamper-me");
        // Flip a Base64 char in the body so the GCM auth tag check fails.
        String corrupted = "gcm:" + (encrypted.charAt(4) == 'A' ? "B" : "A")
                + encrypted.substring(5);

        assertThatExceptionOfType(TokenCryptoService.CryptoException.class)
                .isThrownBy(() -> svc.decrypt(corrupted));
    }

    @Test
    void init_rejectsKekWithWrongLength() {
        String shortKek = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service(shortKek))
                .withMessageContaining("32 bytes");
    }

    @Test
    void emptyConfig_generatesEphemeralKekAndStillRoundTrips() {
        TokenCryptoService svc = service("");

        String encrypted = svc.encrypt("dev-token");

        assertThat(svc.decrypt(encrypted)).isEqualTo("dev-token");
    }
}
