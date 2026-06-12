package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stripe gateway using Stripe Elements + clientSecret flow. The browser confirms the
 * PaymentIntent client-side; the backend receives `payment_intent.succeeded` via webhook.
 * Settles in USD (with amount converted via the canonical USD pricing pipeline upstream).
 *
 * If Stripe is disabled or the secret key is missing, returns a deterministic mock — useful
 * for end-to-end testing without external dependencies.
 */
@Slf4j
@Component
public class StripeGateway implements PaymentGateway {

    @Value("${nexadrop.stripe.enabled:false}")
    private boolean enabled;

    @Value("${nexadrop.stripe.secret-key:}")
    private String secretKey;

    /**
     * Identificador de la plataforma que se inyecta como metadata en Stripe.
     * Cuando varios productos convergen en la misma cuenta Stripe, este campo
     * permite filtrar en el dashboard y en las webhooks (event.data.object.metadata.platform).
     */
    @Value("${nexadrop.stripe.platform-id:nexadrop-dropshipping}")
    private String platformId;

    @Value("${nexadrop.stripe.platform-env:dev}")
    private String platformEnv;

    @Override
    public boolean supports(PaymentMethod m) {
        return m == PaymentMethod.CARD;
    }

    @Override
    public String providerName() {
        return "stripe";
    }

    @Override
    public InitiateResult initiate(PaymentEntity p) {
        if (!isActive()) {
            String mock = "pi_mock_" + p.getId();
            log.info("Stripe mock-mode for payment {}", p.getId());
            return new InitiateResult(mock, mock + "_secret_mock", null, null, null, null, Map.of("mock", true));
        }
        try {
            Stripe.apiKey = secretKey;
            // Metadata: identifica claramente qué plataforma + entidad NX036 originó
            // el cobro, útil cuando varias plataformas convergen en la misma cuenta Stripe.
            // Estos campos aparecen en el dashboard de Stripe y en cada webhook event.
            PaymentIntentCreateParams.Builder b = PaymentIntentCreateParams.builder().setAmount(p.getAmountUsdCents())
                    .setCurrency("usd")
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                    .putMetadata("platform", platformId) // p.ej. "nexadrop-dropshipping"
                    .putMetadata("env", platformEnv) // dev | staging | production
                    .putMetadata("paymentId", p.getId().toString())
                    .putMetadata("userId", p.getUser().getId().toString())
                    .putMetadata("purpose", p.getPurpose() != null ? p.getPurpose() : "WALLET_RECHARGE")
                    .setReceiptEmail(p.getUser().getEmail());

            if (p.getOrderId() != null) {
                b.putMetadata("orderId", p.getOrderId().toString());
                b.setDescription(platformId + " · order " + p.getOrderId());
                // statement_descriptor_suffix aparece en el extracto del cliente final.
                // Máximo 22 chars; sólo letras, números, espacios, puntos.
                String shortOrder = p.getOrderId().toString().substring(0, 8);
                b.setStatementDescriptorSuffix(
                        ("ORD " + shortOrder).substring(0, Math.min(22, ("ORD " + shortOrder).length())));
            } else {
                b.setDescription(platformId + " · wallet recharge");
                b.setStatementDescriptorSuffix("WALLET");
            }
            PaymentIntentCreateParams params = b.build();
            PaymentIntent pi = PaymentIntent.create(params);
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", pi.getId());
            raw.put("status", pi.getStatus());
            raw.put("clientSecret", pi.getClientSecret());
            return new InitiateResult(pi.getId(), pi.getClientSecret(), null, null, null, null, raw);
        } catch (StripeException e) {
            log.error("Stripe initiate failed", e);
            throw new RuntimeException("Stripe payment initiation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        String status = String.valueOf(providerPayload.getOrDefault("status", ""));
        boolean ok = "succeeded".equals(status);
        return new ConfirmResult(ok, ok ? null : "Stripe status: " + status, providerPayload);
    }

    private boolean isActive() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }
}
