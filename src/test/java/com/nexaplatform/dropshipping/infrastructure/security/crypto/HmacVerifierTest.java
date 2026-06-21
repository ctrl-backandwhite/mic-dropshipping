package com.nexaplatform.dropshipping.infrastructure.security.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class HmacVerifierTest {

    private final HmacVerifier verifier = new HmacVerifier();

    @Test
    void sign_matchesIndependentHmacSha256() throws Exception {
        String secret = "s3cr3t";
        byte[] body = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.sign(secret, body)).isEqualTo(hmac(secret, body));
    }

    @Test
    void sign_differsBySecret() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.sign("a", body)).isNotEqualTo(verifier.sign("b", body));
    }

    @Test
    void verify_acceptsRawHexSignature() throws Exception {
        String secret = "shop-shared-secret";
        byte[] body = "order.created".getBytes(StandardCharsets.UTF_8);
        String signature = hmac(secret, body);

        assertThat(verifier.verify(secret, body, signature)).isTrue();
    }

    @Test
    void verify_acceptsSha256PrefixedSignature() throws Exception {
        String secret = "shop-shared-secret";
        byte[] body = "order.created".getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + hmac(secret, body);

        assertThat(verifier.verify(secret, body, signature)).isTrue();
    }

    @Test
    void verify_rejectsInvalidSignature() {
        byte[] body = "order.created".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify("secret", body, "deadbeef")).isFalse();
    }

    @Test
    void verify_rejectsWrongSecret() throws Exception {
        byte[] body = "order.created".getBytes(StandardCharsets.UTF_8);
        String signature = hmac("right", body);

        assertThat(verifier.verify("wrong", body, signature)).isFalse();
    }

    @Test
    void verify_rejectsNullSecretOrHeader() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(null, body, "abc")).isFalse();
        assertThat(verifier.verify("secret", body, null)).isFalse();
    }

    private static String hmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
