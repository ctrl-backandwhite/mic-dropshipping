package com.nexaplatform.dropshipping.infrastructure.security.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 verification helper for inbound webhooks (shop → NexaDrop).
 *
 * The shop signs the raw request body with the connection's shared secret and
 * sends the hex-encoded MAC in the `X-NX-Signature` header.
 *
 * Constant-time comparison guards against timing-based key recovery.
 */
@Component
public class HmacVerifier {

    private static final String ALG = "HmacSHA256";

    /** Computes the hex HMAC-SHA256 of {@code body} using {@code secret} (UTF-8). */
    public String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC", e);
        }
    }

    /** Constant-time check. False if the header is null or malformed. */
    public boolean verify(String secret, byte[] body, String headerSignature) {
        if (secret == null || headerSignature == null)
            return false;
        String expected = sign(secret, body);
        // strip optional "sha256=" prefix for compatibility with Shopify/Stripe-style headers
        String got = headerSignature.startsWith("sha256=") ? headerSignature.substring(7) : headerSignature;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
    }
}
