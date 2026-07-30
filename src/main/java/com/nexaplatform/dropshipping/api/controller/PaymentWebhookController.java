package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.PaymentWebhookApi;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String BAD_SIGNATURE = "bad signature";

    private final PaymentUseCase paymentUseCase;
    /** Aviso al responsable si la pasarela deja de poder confirmar cobros. */
    private final OpsAlertService opsAlertService;

    @Value("${nexadrop.stripe.webhook-secret:}")
    private String stripeWebhookSecret;
    @Value("${nexadrop.paypal.webhook-secret:}")
    private String paypalWebhookSecret;
    @Value("${nexadrop.coinbase.webhook-secret:}")
    private String coinbaseWebhookSecret;

    @Override
    public ResponseEntity<String> stripe(String payload, String sig) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            // Sin secreto el evento NO se procesa (no se puede verificar quién lo manda). Pero tampoco se
            // contesta 2xx: Stripe lo tomaría por entregado y dejaría de reintentar, así que los pagos con
            // tarjeta se quedarían sin confirmar y nadie se enteraría. Con 5xx los reintenta durante días,
            // y en cuanto se configure el secreto entran solos.
            log.error("::> [PAYMENT] Webhook de Stripe recibido sin secreto configurado: evento NO procesado");
            opsAlertService.paymentFailed("stripe", "recibir el webhook", null,
                    "No hay STRIPE_WEBHOOK_SECRET configurado: los eventos de pago no se pueden verificar "
                            + "y quedan sin procesar. Los cobros con tarjeta no se confirmarán hasta "
                            + "configurarlo.");
            return ResponseEntity.status(503).body("webhook secret not configured");
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, sig, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(400).body(BAD_SIGNATURE);
        }
        log.info("Stripe webhook: {}", event.getType());
        return ResponseEntity.ok(paymentUseCase.handleStripeEvent(event.getType(), payload));
    }

    @Override
    public ResponseEntity<String> paypal(String payload, String sig) {
        if (!verifyHmac(payload, sig, paypalWebhookSecret)) {
            return ResponseEntity.status(400).body(BAD_SIGNATURE);
        }
        return ResponseEntity.ok(paymentUseCase.handlePayPalEvent(payload));
    }

    @Override
    public ResponseEntity<String> coinbase(String payload, String sig) {
        if (!verifyHmac(payload, sig, coinbaseWebhookSecret)) {
            return ResponseEntity.status(400).body(BAD_SIGNATURE);
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
        byte[] provided = decodeHex(signature);
        if (provided.length == 0) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            // Comparación en tiempo constante para no filtrar la firma por timing.
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            log.error("HMAC verification error", e);
            return false;
        }
    }

    /**
     * Firma hexadecimal a bytes, o array vacío si no es hexadecimal válido. Una firma mal formada es
     * simplemente un webhook a rechazar, no un error del servidor: por eso se traduce a "sin firma" en
     * vez de propagar la excepción. Se devuelve vacío y no {@code null} para que quien llama no tenga que
     * distinguir dos formas de "no hay firma" (la vacía ya se rechaza antes por firma en blanco).
     */
    private static byte[] decodeHex(String signature) {
        try {
            return HexFormat.of().parseHex(signature.trim().toLowerCase());
        } catch (IllegalArgumentException badHex) {
            return new byte[0];
        }
    }
}
