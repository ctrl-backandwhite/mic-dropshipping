package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PayPal v2 REST: Orders Create → buyer approves → Orders Capture.
 *
 * Pattern: server creates an "order" with `intent=CAPTURE` in USD; PayPal returns an
 * `approve_url` for the buyer; the browser redirects there; on return we call
 * `/v2/checkout/orders/{id}/capture` (server-side) and confirm the payment.
 *
 * Auth: OAuth2 client_credentials against `/v1/oauth2/token`.
 */
@Slf4j
@Component
public class PayPalGateway implements PaymentGateway {

    /** Tipo de respuesta de PayPal. Con {@code Map.class} el genérico se pierde y hace falta castear. */
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String APPLICATION_JSON = "application/json";
    private static final String AUTHORIZATION = "Authorization";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String COMPLETED = "COMPLETED";
    private static final String BEARER = "Bearer ";
    private static final String STATUS = "status";

    @Value("${nexadrop.paypal.enabled:false}")
    private boolean enabled;
    @Value("${nexadrop.paypal.client-id:}")
    private String clientId;
    @Value("${nexadrop.paypal.client-secret:}")
    private String clientSecret;
    @Value("${nexadrop.paypal.base-url:https://api-m.sandbox.paypal.com}")
    private String baseUrl;
    @Value("${nexadrop.paypal.return-url:http://localhost:3003/wallet/paypal-return}")
    private String returnUrl;
    @Value("${nexadrop.paypal.cancel-url:http://localhost:3003/wallet/recharge?cancelled=1}")
    private String cancelUrl;
    @Value("${nexadrop.paypal.platform-id:nexadrop-dropshipping}")
    private String platformId;
    @Value("${nexadrop.paypal.platform-env:dev}")
    private String platformEnv;
    /** Base pública del storefront, para la URL de retorno de los pagos de pedido. */
    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

    private final WebClient.Builder webClientBuilder;
    public PayPalGateway(WebClient.Builder b) {
        this.webClientBuilder = b;
    }

    @Override
    public boolean supports(PaymentMethod m) {
        return m == PaymentMethod.PAYPAL;
    }

    @Override
    public String providerName() {
        return "paypal";
    }

    @Override
    public InitiateResult initiate(PaymentEntity p) {
        boolean isOrder = p.getOrderId() != null;
        // Pagos de pedido vuelven al retorno unificado del checkout; recargas de wallet, al de wallet.
        String effReturnUrl = isOrder
                ? storefrontBaseUrl + "/checkout/return?provider=paypal&orderId=" + p.getOrderId() + "&paymentId="
                        + p.getId()
                : returnUrl;
        String effCancelUrl = isOrder ? storefrontBaseUrl + "/checkout?cancelled=1" : cancelUrl;

        if (!isActive()) {
            String mock = "paypal_mock_" + p.getId();
            log.info("PayPal mock-mode for payment {}", p.getId());
            String mockReturn = isOrder ? effReturnUrl + "&mock=1" : "/wallet/paypal-return?token=" + mock + "&mock=1";
            return new InitiateResult(mock, null, mockReturn, null, null, null, Map.of("mock", true));
        }
        String token = fetchAccessToken();
        BigDecimal amount = BigDecimal.valueOf(p.getAmountUsdCents()).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);

        // PayPal expone `custom_id` (max 127 chars) y `invoice_id` por purchase_unit:
        // los usamos para identificar la plataforma + entidad NX036.
        String description = platformId + " · " + (isOrder ? "order " + p.getOrderId() : "wallet recharge");
        String customId = platformId + ":" + (isOrder ? "order:" + p.getOrderId() : "wallet:" + p.getId());

        Map<String, Object> purchase = new HashMap<>();
        purchase.put("reference_id", p.getId().toString());
        purchase.put("amount", Map.of("currency_code", "USD", "value", amount.toPlainString()));
        purchase.put("description", description);
        purchase.put("custom_id", customId);
        if (isOrder)
            purchase.put("invoice_id", p.getOrderId().toString());

        Map<String, Object> body = Map.of("intent", "CAPTURE", "purchase_units", List.of(purchase),
                "application_context", Map.of("brand_name", "NX036 (" + platformEnv + ")", "user_action",
                        "PAY_NOW", "return_url", effReturnUrl, "cancel_url", effCancelUrl));

        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(baseUrl + "/v2/checkout/orders").header(AUTHORIZATION, BEARER + token)
                .header("PayPal-Request-Id",
                        p.getIdempotencyKey() != null ? p.getIdempotencyKey() : p.getId().toString())
                .header(CONTENT_TYPE, APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(MAP_TYPE)
                .timeout(Duration.ofSeconds(20)).block();

        String orderId = String.valueOf(resp.get("id"));
        String approveUrl = null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) resp.get("links");
        if (links != null) {
            for (Map<String, Object> link : links) {
                if ("approve".equals(link.get("rel"))) {
                    approveUrl = String.valueOf(link.get("href"));
                    break;
                }
            }
        }
        return new InitiateResult(orderId, null, approveUrl, null, null, null, resp);
    }

    /** Server-side capture. Called by frontend on PayPal return. */
    public Map<String, Object> capture(String paypalOrderId) {
        if (!isActive())
            return Map.of(STATUS, COMPLETED, "mock", true);
        String token = fetchAccessToken();
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(baseUrl + "/v2/checkout/orders/" + paypalOrderId + "/capture")
                .header(AUTHORIZATION, BEARER + token).header(CONTENT_TYPE, APPLICATION_JSON)
                .bodyValue(Map.of()).retrieve().bodyToMono(MAP_TYPE).timeout(Duration.ofSeconds(20)).block();
        return resp != null ? resp : new HashMap<>();
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        String status = String.valueOf(providerPayload.getOrDefault(STATUS, ""));
        boolean ok = COMPLETED.equalsIgnoreCase(status);
        return new ConfirmResult(ok, ok ? null : "PayPal status: " + status, providerPayload);
    }

    /**
     * Refunds a captured payment. {@code captureId} is the capture id returned by
     * {@link #capture(String)} (see {@link #extractCaptureId(Map)}). Full refund when
     * {@code amountCents <= 0}; otherwise a partial refund of that USD amount.
     */
    public Map<String, Object> refund(String captureId, long amountCents) {
        if (!isActive())
            return Map.of(STATUS, COMPLETED, "mock", true);
        String token = fetchAccessToken();
        Map<String, Object> body = amountCents > 0
                ? Map.of("amount", Map.of("currency_code", "USD", "value",
                        BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                                .toPlainString()))
                : Map.of();
        Map<String, Object> resp = webClientBuilder.build().post()
                .uri(baseUrl + "/v2/payments/captures/" + captureId + "/refund")
                .header(AUTHORIZATION, BEARER + token).header(CONTENT_TYPE, APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(MAP_TYPE).timeout(Duration.ofSeconds(20)).block();
        return resp != null ? resp : new HashMap<>();
    }

    /**
     * Digs the capture id out of a PayPal Orders Capture response
     * ({@code purchase_units[0].payments.captures[0].id}). Returns null if absent.
     */
    @SuppressWarnings("unchecked")
    public static String extractCaptureId(Map<String, Object> captureResponse) {
        if (captureResponse == null)
            return null;
        Object units = captureResponse.get("purchase_units");
        if (!(units instanceof List<?> unitList) || unitList.isEmpty())
            return null;
        Object first = unitList.get(0);
        if (!(first instanceof Map<?, ?> unit))
            return null;
        Object payments = ((Map<String, Object>) unit).get("payments");
        if (!(payments instanceof Map<?, ?> pay))
            return null;
        Object captures = ((Map<String, Object>) pay).get("captures");
        if (!(captures instanceof List<?> capList) || capList.isEmpty())
            return null;
        Object cap = capList.get(0);
        return cap instanceof Map<?, ?> capMap ? String.valueOf(((Map<String, Object>) capMap).get("id")) : null;
    }

    private String fetchAccessToken() {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
        Map<String, Object> body = webClientBuilder.build().post()
                .uri(baseUrl + "/v1/oauth2/token").header(AUTHORIZATION, "Basic " + basic)
                .header(CONTENT_TYPE, "application/x-www-form-urlencoded").bodyValue("grant_type=client_credentials")
                .retrieve().bodyToMono(MAP_TYPE).timeout(Duration.ofSeconds(15)).block();
        return String.valueOf(body.get("access_token"));
    }

    private boolean isActive() {
        return enabled && clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
