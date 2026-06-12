package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
        if (!isActive()) {
            String mock = "paypal_mock_" + p.getId();
            log.info("PayPal mock-mode for payment {}", p.getId());
            return new InitiateResult(mock, null, "/wallet/paypal-return?token=" + mock + "&mock=1", null, null, null,
                    Map.of("mock", true));
        }
        String token = fetchAccessToken();
        BigDecimal amount = BigDecimal.valueOf(p.getAmountUsdCents()).divide(BigDecimal.valueOf(100), 2,
                RoundingMode.HALF_UP);

        // PayPal expone `custom_id` (max 127 chars) y `invoice_id` por purchase_unit:
        // los usamos para identificar la plataforma + entidad NX036.
        boolean isOrder = p.getOrderId() != null;
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
                "application_context", Map.of("brand_name", "NX036 Dropshipping (" + platformEnv + ")", "user_action",
                        "PAY_NOW", "return_url", returnUrl, "cancel_url", cancelUrl));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) webClientBuilder.build().post()
                .uri(baseUrl + "/v2/checkout/orders").header("Authorization", "Bearer " + token)
                .header("PayPal-Request-Id",
                        p.getIdempotencyKey() != null ? p.getIdempotencyKey() : p.getId().toString())
                .header("Content-Type", "application/json").bodyValue(body).retrieve().bodyToMono(Map.class)
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
            return Map.of("status", "COMPLETED", "mock", true);
        String token = fetchAccessToken();
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) webClientBuilder.build().post()
                .uri(baseUrl + "/v2/checkout/orders/" + paypalOrderId + "/capture")
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .bodyValue(Map.of()).retrieve().bodyToMono(Map.class).timeout(Duration.ofSeconds(20)).block();
        return resp != null ? resp : new HashMap<>();
    }

    @Override
    public ConfirmResult confirm(PaymentEntity p, Map<String, Object> providerPayload) {
        String status = String.valueOf(providerPayload.getOrDefault("status", ""));
        boolean ok = "COMPLETED".equalsIgnoreCase(status);
        return new ConfirmResult(ok, ok ? null : "PayPal status: " + status, providerPayload);
    }

    private String fetchAccessToken() {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) webClientBuilder.build().post()
                .uri(baseUrl + "/v1/oauth2/token").header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded").bodyValue("grant_type=client_credentials")
                .retrieve().bodyToMono(Map.class).timeout(Duration.ofSeconds(15)).block();
        return String.valueOf(body.get("access_token"));
    }

    private boolean isActive() {
        return enabled && clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
