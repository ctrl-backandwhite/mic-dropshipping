package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stripe gateway. Two flows depending on the payment purpose:
 *
 * <ul>
 *   <li><b>Order checkout</b> (orderId present): hosted <b>Checkout Session</b> (mode=payment).
 *       The buyer is redirected to Stripe's PCI-compliant page and back to {@code /checkout/return};
 *       the server then confirms by retrieving the session. No card data ever touches our frontend.</li>
 *   <li><b>Wallet recharge</b> (no orderId): {@link PaymentIntent} + clientSecret (Elements) flow.</li>
 * </ul>
 *
 * Settles in USD. If Stripe is disabled or the secret key is missing, returns a deterministic mock
 * so the end-to-end flow works without external dependencies.
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

    /** Base pública del storefront, para componer las URLs de retorno/cancelación del Checkout. */
    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

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
        boolean isOrder = p.getOrderId() != null;
        if (!isActive()) {
            if (isOrder) {
                String mock = "cs_mock_" + p.getId();
                String url = storefrontBaseUrl + "/checkout/return?provider=stripe&orderId=" + p.getOrderId()
                        + "&paymentId=" + p.getId() + "&mock=1";
                log.info("Stripe mock-mode (order checkout) for payment {}", p.getId());
                return new InitiateResult(mock, null, url, null, null, null, Map.of("mock", true));
            }
            String mock = "pi_mock_" + p.getId();
            log.info("Stripe mock-mode (wallet) for payment {}", p.getId());
            return new InitiateResult(mock, mock + "_secret_mock", null, null, null, null, Map.of("mock", true));
        }
        Stripe.apiKey = secretKey;
        return isOrder ? initiateCheckoutSession(p) : initiatePaymentIntent(p);
    }

    /** Order checkout → hosted Stripe Checkout Session (redirect flow). */
    private InitiateResult initiateCheckoutSession(PaymentEntity p) {
        try {
            String successUrl = storefrontBaseUrl + "/checkout/return?provider=stripe&orderId=" + p.getOrderId()
                    + "&paymentId=" + p.getId() + "&session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = storefrontBaseUrl + "/checkout?cancelled=1";

            // Moneda de cobro = la liquidación fijada al iniciar el pago (EUR si el usuario navega en EUR,
            // USD en cualquier otro caso). El monto va en esa moneda (céntimos).
            String chargeCcy = "EUR".equalsIgnoreCase(p.getSettlementCurrency()) ? "eur" : "usd";
            long chargeCents = chargeCents(p);

            SessionCreateParams params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl).setCancelUrl(cancelUrl).setCustomerEmail(p.getUser().getEmail())
                    .addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder().setCurrency(chargeCcy)
                                    .setUnitAmount(chargeCents)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("NX036 Dropshipping · order " + shortId(p.getOrderId().toString()))
                                            .build())
                                    .build())
                            .build())
                    // Metadata en la sesión y en el PaymentIntent resultante → viaja al cargo/recibo.
                    .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                    .putMetadata("orderId", p.getOrderId().toString()).putMetadata("paymentId", p.getId().toString())
                    .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                            .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                            .putMetadata("orderId", p.getOrderId().toString())
                            .putMetadata("paymentId", p.getId().toString())
                            .setDescription(platformId + " · order " + p.getOrderId()).build())
                    .build();

            Session session = Session.create(params);
            Map<String, Object> raw = new HashMap<>();
            raw.put("id", session.getId());
            raw.put("approveUrl", session.getUrl());
            raw.put("paymentIntent", session.getPaymentIntent());
            return new InitiateResult(session.getId(), null, session.getUrl(), null, null, null, raw);
        } catch (StripeException e) {
            log.error("Stripe checkout session creation failed", e);
            throw new RuntimeException("Stripe checkout session failed: " + e.getMessage(), e);
        }
    }

    /** Wallet recharge → PaymentIntent + clientSecret (Elements). */
    private InitiateResult initiatePaymentIntent(PaymentEntity p) {
        try {
            PaymentIntentCreateParams.Builder b = PaymentIntentCreateParams.builder().setAmount(p.getAmountUsdCents())
                    .setCurrency("usd")
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build())
                    .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                    .putMetadata("paymentId", p.getId().toString()).putMetadata("userId", p.getUser().getId().toString())
                    .putMetadata("purpose", p.getPurpose() != null ? p.getPurpose() : "WALLET_RECHARGE")
                    .setReceiptEmail(p.getUser().getEmail()).setDescription(platformId + " · wallet recharge")
                    .setStatementDescriptorSuffix("WALLET");
            PaymentIntent pi = PaymentIntent.create(b.build());
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

    /**
     * Retrieves a Checkout Session and reports whether it settled. Used by the server-side
     * confirmation on return (we do not rely on the webhook in local/sandbox runs).
     * Returns {@code status=paid} when {@code payment_status == "paid"}, plus the underlying
     * payment_intent id so a later refund can target the charge.
     */
    public Map<String, Object> retrieveCheckoutSession(String sessionId) {
        if (!isActive()) {
            return Map.of("status", "paid", "mock", true);
        }
        try {
            Stripe.apiKey = secretKey;
            Session session = Session.retrieve(sessionId);
            String paymentStatus = session.getPaymentStatus(); // "paid" | "unpaid" | "no_payment_required"
            Map<String, Object> out = new HashMap<>();
            out.put("status", "paid".equals(paymentStatus) ? "paid" : paymentStatus);
            out.put("payment_status", paymentStatus);
            out.put("paymentIntent", session.getPaymentIntent());
            return out;
        } catch (StripeException e) {
            log.error("Stripe session retrieve failed for {}", sessionId, e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    /**
     * Refunds a settled charge by its PaymentIntent id (full or partial). Returns the Stripe
     * refund status ({@code succeeded}/{@code pending}/...). In mock-mode returns a synthetic success.
     */
    public Map<String, Object> refund(String paymentIntentId, long amountCents) {
        if (!isActive()) {
            return Map.of("status", "succeeded", "mock", true);
        }
        try {
            Stripe.apiKey = secretKey;
            RefundCreateParams.Builder b = RefundCreateParams.builder().setPaymentIntent(paymentIntentId)
                    .putMetadata("platform", platformId).putMetadata("env", platformEnv);
            if (amountCents > 0) {
                b.setAmount(amountCents);
            }
            Refund refund = Refund.create(b.build());
            Map<String, Object> out = new HashMap<>();
            out.put("id", refund.getId());
            out.put("status", refund.getStatus());
            return out;
        } catch (StripeException e) {
            log.error("Stripe refund failed for PI {}", paymentIntentId, e);
            return Map.of("status", "failed", "error", e.getMessage());
        }
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        String status = String.valueOf(providerPayload.getOrDefault("status", ""));
        boolean ok = "succeeded".equals(status) || "paid".equals(status);
        return new ConfirmResult(ok, ok ? null : "Stripe status: " + status, providerPayload);
    }

    private String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Monto a cobrar en céntimos de la moneda de liquidación (settlementAmount); fallback al USD canónico. */
    private long chargeCents(PaymentEntity p) {
        if (p.getSettlementAmount() != null) {
            return p.getSettlementAmount().movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        }
        return p.getAmountUsdCents();
    }

    private boolean isActive() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }
}
