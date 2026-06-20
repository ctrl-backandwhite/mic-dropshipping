package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PaymentWebhookApi;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Webhook endpoints — signature verified per provider (transport security stays
 * here); payload parsing and dispatch are delegated to {@link PaymentUseCase}.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController implements PaymentWebhookApi {

    private final PaymentUseCase paymentUseCase;

    @Value("${nexadrop.stripe.webhook-secret:}")
    private String stripeWebhookSecret;
    @Value("${nexadrop.paypal.webhook-secret:}")
    private String paypalWebhookSecret;
    @Value("${nexadrop.coinbase.webhook-secret:}")
    private String coinbaseWebhookSecret;

    @Override
    public ResponseEntity<String> stripe(String payload, String sig) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            log.warn("Stripe webhook hit with no secret configured");
            return ResponseEntity.ok("ignored");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, sig, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(400).body("bad signature");
        }
        log.info("Stripe webhook: {}", event.getType());
        return ResponseEntity.ok(paymentUseCase.handleStripeEvent(event.getType(), payload));
    }

    @Override
    public ResponseEntity<String> paypal(String payload, String sig) {
        if (!verifyHmac(payload, sig, paypalWebhookSecret)) {
            return ResponseEntity.status(400).body("bad signature");
        }
        return ResponseEntity.ok(paymentUseCase.handlePayPalEvent(payload));
    }

    @Override
    public ResponseEntity<String> coinbase(String payload, String sig) {
        if (!verifyHmac(payload, sig, coinbaseWebhookSecret)) {
            return ResponseEntity.status(400).body("bad signature");
        }
        return ResponseEntity.ok(paymentUseCase.handleCoinbaseEvent(payload));
    }

    private boolean verifyHmac(String payload, String signature, String secret) {
        // Fail-closed: sin secreto configurado NO se acepta ningún webhook (antes se
        // devolvía true → cualquiera podía falsificar un pago y marcar órdenes pagadas).
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook rejected: no secret configured for this provider");
            return false;
        }
        if (signature == null || signature.isBlank())
            return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] provided;
            try {
                provided = HexFormat.of().parseHex(signature.trim().toLowerCase());
            } catch (IllegalArgumentException badHex) {
                return false;
            }
            // Comparación en tiempo constante para no filtrar la firma por timing.
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            log.error("HMAC verification error", e);
            return false;
        }
    }
}
