package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Stripe gateway. Tanto el <b>pago de pedido</b> (orderId presente) como la <b>recarga de wallet</b>
 * (sin orderId) usan el MISMO <b>Checkout Session</b> hospedado (mode=payment): el comprador se
 * redirige a la página PCI de Stripe y vuelve a {@code /checkout/return} o {@code /wallet/recharge/return},
 * y el servidor confirma recuperando la sesión. Ningún dato de tarjeta pasa por nuestro frontend.
 *
 * Cobra en EUR si el usuario trabaja la web en EUR; en USD en cualquier otro caso. Si Stripe está
 * deshabilitado o falta la clave, devuelve un mock determinista para que el flujo end-to-end funcione
 * sin dependencias externas.
 */
@Slf4j
@Component
public class StripeGateway implements PaymentGateway {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String PLATFORM = "platform";
    private static final String STATUS = "status";
    private static final String ERROR = "error";

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
            // Mock-mode: tanto pedido como recarga usan el flujo de Checkout hospedado (redirect a la
            // página de retorno correspondiente, que confirma del lado servidor).
            String mock = "cs_mock_" + p.getId();
            String url = isOrder
                    ? storefrontBaseUrl + "/checkout/return?provider=stripe&orderId=" + p.getOrderId()
                            + "&paymentId=" + p.getId() + "&mock=1"
                    : storefrontBaseUrl + "/wallet/recharge/return?provider=stripe&paymentId=" + p.getId() + "&mock=1";
            log.info("Stripe mock-mode ({}) for payment {}", isOrder ? "order checkout" : "wallet recharge", p.getId());
            return new InitiateResult(mock, null, url, null, null, null, Map.of("mock", true));
        }
        applyApiKey(secretKey);
        // Recarga de wallet Y pago de pedido usan el MISMO Stripe Checkout hospedado (redirect), para que
        // la tarjeta se introduzca en la página segura de Stripe y el cobro se confirme al volver.
        return initiateCheckoutSession(p);
    }

    /** Checkout hospedado (redirect) para pago de PEDIDO o RECARGA de wallet. */
    private InitiateResult initiateCheckoutSession(PaymentEntity p) {
        try {
            boolean isOrder = p.getOrderId() != null;
            String successUrl = isOrder
                    ? storefrontBaseUrl + "/checkout/return?provider=stripe&orderId=" + p.getOrderId()
                            + "&paymentId=" + p.getId() + "&session_id={CHECKOUT_SESSION_ID}"
                    : storefrontBaseUrl + "/wallet/recharge/return?provider=stripe&paymentId=" + p.getId()
                            + "&session_id={CHECKOUT_SESSION_ID}";
            String cancelUrl = isOrder ? storefrontBaseUrl + "/checkout?cancelled=1"
                    : storefrontBaseUrl + "/wallet/recharge?cancelled=1";
            String productName = isOrder
                    ? "NX036 LTD · order " + shortId(p.getOrderId().toString())
                    : "NX036 LTD · wallet recharge";
            String description = isOrder ? platformId + " · order " + p.getOrderId()
                    : platformId + " · wallet recharge";

            // Moneda de cobro = la liquidación fijada al iniciar el pago (EUR si el usuario navega en EUR,
            // USD en cualquier otro caso). El monto va en esa moneda (céntimos).
            String chargeCcy = "EUR".equalsIgnoreCase(p.getSettlementCurrency()) ? "eur" : "usd";
            long chargeCents = chargeCents(p);

            SessionCreateParams.PaymentIntentData.Builder piData = SessionCreateParams.PaymentIntentData.builder()
                    .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                    .putMetadata("paymentId", p.getId().toString()).setDescription(description);
            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl).setCancelUrl(cancelUrl).setCustomerEmail(p.getUser().getEmail())
                    .addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder().setCurrency(chargeCcy)
                                    .setUnitAmount(chargeCents)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(productName).build())
                                    .build())
                            .build())
                    // Metadata en la sesión y en el PaymentIntent resultante → viaja al cargo/recibo.
                    .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                    .putMetadata("paymentId", p.getId().toString());
            if (isOrder) {
                builder.putMetadata("orderId", p.getOrderId().toString());
                piData.putMetadata("orderId", p.getOrderId().toString());
            } else {
                builder.putMetadata("purpose", "WALLET_RECHARGE");
                piData.putMetadata("purpose", "WALLET_RECHARGE");
            }
            SessionCreateParams params = builder.setPaymentIntentData(piData.build()).build();

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


    /**
     * Retrieves a Checkout Session and reports whether it settled. Used by the server-side
     * confirmation on return (we do not rely on the webhook in local/sandbox runs).
     * Returns {@code status=paid} when {@code payment_status == "paid"}, plus the underlying
     * payment_intent id so a later refund can target the charge.
     */
    public Map<String, Object> retrieveCheckoutSession(String sessionId) {
        if (!isActive()) {
            return Map.of(STATUS, "paid", "mock", true);
        }
        try {
            applyApiKey(secretKey);
            Session session = Session.retrieve(sessionId);
            String paymentStatus = session.getPaymentStatus(); // "paid" | "unpaid" | "no_payment_required"
            Map<String, Object> out = new HashMap<>();
            out.put(STATUS, "paid".equals(paymentStatus) ? "paid" : paymentStatus);
            out.put("payment_status", paymentStatus);
            out.put("paymentIntent", session.getPaymentIntent());
            return out;
        } catch (StripeException e) {
            log.error("Stripe session retrieve failed for {}", sessionId, e);
            return Map.of(STATUS, ERROR, ERROR, e.getMessage());
        }
    }

    /**
     * Refunds a settled charge by its PaymentIntent id (full or partial). Returns the Stripe
     * refund status ({@code succeeded}/{@code pending}/...). In mock-mode returns a synthetic success.
     */
    public Map<String, Object> refund(String paymentIntentId, long amountCents) {
        if (!isActive()) {
            return Map.of(STATUS, "succeeded", "mock", true);
        }
        try {
            applyApiKey(secretKey);
            RefundCreateParams.Builder b = RefundCreateParams.builder().setPaymentIntent(paymentIntentId)
                    .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv);
            if (amountCents > 0) {
                b.setAmount(amountCents);
            }
            Refund refund = Refund.create(b.build());
            Map<String, Object> out = new HashMap<>();
            out.put("id", refund.getId());
            out.put(STATUS, refund.getStatus());
            return out;
        } catch (StripeException e) {
            log.error("Stripe refund failed for PI {}", paymentIntentId, e);
            return Map.of(STATUS, "failed", ERROR, e.getMessage());
        }
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        String status = String.valueOf(providerPayload.getOrDefault(STATUS, ""));
        boolean ok = "succeeded".equals(status) || "paid".equals(status);
        return new ConfirmResult(ok, ok ? null : "Stripe status: " + status, providerPayload);
    }

    private String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Monto a cobrar en céntimos de la moneda de liquidación (settlementAmount); fallback al USD canónico. */
    private long chargeCents(PaymentEntity p) {
        if (p.getSettlementAmount() != null) {
            return p.getSettlementAmount().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        return p.getAmountUsdCents();
    }

    private boolean isActive() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    /**
     * Publica la clave secreta en la configuración GLOBAL del SDK de Stripe, que es de donde la leen sus
     * métodos estáticos ({@code Session.create}, {@code Refund.create}…). Se centraliza aquí, en un único
     * método estático, en vez de repetir la asignación en cada llamada: así hay un solo punto que toca
     * estado global y queda explicado por qué.
     *
     * <p>La alternativa sería pasar {@code RequestOptions} con la clave en cada llamada, que evitaría el
     * estado global por completo; obliga a cambiar la sobrecarga usada en las tres llamadas y a rehacer
     * los stubs estáticos de las pruebas, así que se deja como mejora aparte.
     */
    private static synchronized void applyApiKey(String key) {
        Stripe.apiKey = key;
    }
}
