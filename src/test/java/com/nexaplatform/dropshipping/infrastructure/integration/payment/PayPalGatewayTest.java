package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway.ConfirmResult;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway.InitiateResult;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentMatchers;
import org.springframework.core.ParameterizedTypeReference;

class PayPalGatewayTest {

    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private WebClient webClient;
    private WebClient.RequestBodyUriSpec uriSpec;
    private WebClient.RequestBodySpec bodySpec;
    private WebClient.RequestHeadersSpec<?> headersSpec;
    private WebClient.ResponseSpec responseSpec;

    private PayPalGateway gateway;

    /**
     * Each call to {@code webClientBuilder.build()} returns a fresh WebClient whose fluent chain
     * funnels into the same response spec; per-test we enqueue the JSON bodies returned by
     * {@code bodyToMono(...)} in call order (token first, then the actual operation).
     */
    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        webClient = mock(WebClient.class);
        uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        bodySpec = mock(WebClient.RequestBodySpec.class);
        headersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(builder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);

        gateway = new PayPalGateway(builder);
        ReflectionTestUtils.setField(gateway, "clientId", "cid");
        ReflectionTestUtils.setField(gateway, "clientSecret", "csecret");
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://paypal.test");
        ReflectionTestUtils.setField(gateway, "returnUrl", "https://shop.test/wallet/paypal-return");
        ReflectionTestUtils.setField(gateway, "cancelUrl", "https://shop.test/wallet/recharge?cancelled=1");
        ReflectionTestUtils.setField(gateway, "platformId", "nexadrop-test");
        ReflectionTestUtils.setField(gateway, "platformEnv", "test");
        ReflectionTestUtils.setField(gateway, "storefrontBaseUrl", "https://shop.test");
    }

    private void enable() {
        ReflectionTestUtils.setField(gateway, "enabled", true);
    }

    /** Enqueue the Map responses returned by consecutive bodyToMono(...).block() calls. */
    @SuppressWarnings("unchecked")
    private void enqueueResponses(Map<String, Object> first, Map<String, Object>... rest) {
        Mono<Map<String, Object>> firstMono = Mono.just(first);
        Mono<Map<String, Object>>[] restMonos = new Mono[rest.length];
        for (int i = 0; i < rest.length; i++) {
            restMonos[i] = Mono.just(rest[i]);
        }
        // Real Monos: the gateway chains .timeout(...).block() on them, which executes for real.
        // El gateway pide el tipo parametrizado, no Map.class: con el tipo crudo el genérico se perdía
        // y obligaba a castear la respuesta con @SuppressWarnings.
        when(responseSpec.bodyToMono(ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
                .thenReturn((Mono) firstMono, (Mono[]) restMonos);
    }

    private PaymentEntity payment(UUID orderId, long usdCents, String idempotencyKey) {
        UserEntity user = mock(UserEntity.class);

        PaymentEntity p = mock(PaymentEntity.class);
        when(p.getId()).thenReturn(PAYMENT_ID);
        when(p.getOrderId()).thenReturn(orderId);
        when(p.getUser()).thenReturn(user);
        when(p.getAmountUsdCents()).thenReturn(usdCents);
        when(p.getIdempotencyKey()).thenReturn(idempotencyKey);
        return p;
    }

    // ---------------------------------------------------------------- supports / providerName

    @Test
    void supportsOnlyPaypal() {
        assertThat(gateway.supports(PaymentMethod.PAYPAL)).isTrue();
        assertThat(gateway.supports(PaymentMethod.CARD)).isFalse();
        assertThat(gateway.providerName()).isEqualTo("paypal");
    }

    // ---------------------------------------------------------------- mock mode (disabled)

    @Test
    void initiateMockModeOrder() {
        PaymentEntity p = payment(ORDER_ID, 5_000L, null);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("paypal_mock_" + PAYMENT_ID);
        assertThat(r.approveUrl()).contains("/checkout/return").contains("orderId=" + ORDER_ID).contains("mock=1");
        assertThat(r.raw()).containsEntry("mock", true);
    }

    @Test
    void initiateMockModeWallet() {
        PaymentEntity p = payment(null, 5_000L, null);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("paypal_mock_" + PAYMENT_ID);
        assertThat(r.approveUrl()).startsWith("/wallet/paypal-return?token=paypal_mock_").contains("mock=1");
    }

    // ---------------------------------------------------------------- initiate (real, order)

    @Test
    void initiateOrderCreatesOrderAndExtractsApproveUrl() {
        enable();
        PaymentEntity p = payment(ORDER_ID, 12_345L, "idem-key-1");

        Map<String, Object> tokenResp = Map.of("access_token", "tok_abc");
        Map<String, Object> orderResp = Map.of("id", "ORDER-1", "status", "CREATED", "links",
                List.of(Map.of("rel", "self", "href", "https://paypal.test/self"),
                        Map.of("rel", "approve", "href", "https://paypal.test/approve/ORDER-1")));
        enqueueResponses(tokenResp, orderResp);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("ORDER-1");
        assertThat(r.approveUrl()).isEqualTo("https://paypal.test/approve/ORDER-1");
        assertThat(r.clientSecret()).isNull();
        assertThat(r.raw()).containsEntry("status", "CREATED");

        // 12345 USD cents -> 123.45 amount value sent to PayPal.
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec, Mockito.atLeastOnce()).bodyValue(bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = bodyCaptor.getAllValues().stream()
                .filter(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("intent")).map(b -> (Map<String, Object>) b)
                .findFirst().orElseThrow();
        assertThat(orderBody).containsEntry("intent", "CAPTURE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) orderBody.get("purchase_units");
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) units.get(0).get("amount");
        assertThat(amount).containsEntry("currency_code", "USD").containsEntry("value", "123.45");
        assertThat(units.get(0)).containsEntry("invoice_id", ORDER_ID.toString());

        // Idempotency key flows into PayPal-Request-Id header.
        verify(bodySpec).header("PayPal-Request-Id", "idem-key-1");
    }

    @Test
    void initiateWalletConvertsAmountAndOmitsInvoiceId() {
        enable();
        PaymentEntity p = payment(null, 100L, null); // 1.00 USD, no idempotency key

        Map<String, Object> tokenResp = Map.of("access_token", "tok_w");
        Map<String, Object> orderResp = Map.of("id", "ORDER-W", "links",
                List.of(Map.of("rel", "approve", "href", "https://paypal.test/approve/W")));
        enqueueResponses(tokenResp, orderResp);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("ORDER-W");
        assertThat(r.approveUrl()).isEqualTo("https://paypal.test/approve/W");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec, Mockito.atLeastOnce()).bodyValue(bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> orderBody = bodyCaptor.getAllValues().stream()
                .filter(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("intent")).map(b -> (Map<String, Object>) b)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) orderBody.get("purchase_units");
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) units.get(0).get("amount");
        assertThat(amount).containsEntry("value", "1.00");
        assertThat(units.get(0)).doesNotContainKey("invoice_id");

        // No idempotency key -> falls back to payment id in PayPal-Request-Id.
        verify(bodySpec).header("PayPal-Request-Id", PAYMENT_ID.toString());
    }

    @Test
    void initiateLeavesApproveUrlNullWhenNoApproveLink() {
        enable();
        PaymentEntity p = payment(null, 500L, null);

        Map<String, Object> tokenResp = Map.of("access_token", "tok");
        Map<String, Object> orderResp = Map.of("id", "ORDER-X", "links",
                List.of(Map.of("rel", "self", "href", "https://paypal.test/self")));
        enqueueResponses(tokenResp, orderResp);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("ORDER-X");
        assertThat(r.approveUrl()).isNull();
    }

    // ---------------------------------------------------------------- capture

    @Test
    void captureMockWhenDisabled() {
        Map<String, Object> out = gateway.capture("ORDER-1");
        assertThat(out).containsEntry("status", "COMPLETED").containsEntry("mock", true);
    }

    @Test
    void captureReturnsResponseBody() {
        enable();
        Map<String, Object> tokenResp = Map.of("access_token", "tok");
        Map<String, Object> captureResp = Map.of("id", "ORDER-1", "status", "COMPLETED");
        enqueueResponses(tokenResp, captureResp);

        Map<String, Object> out = gateway.capture("ORDER-1");

        assertThat(out).containsEntry("status", "COMPLETED");
        verify(uriSpec).uri("https://paypal.test/v2/checkout/orders/ORDER-1/capture");
    }

    // ---------------------------------------------------------------- refund

    @Test
    void refundMockWhenDisabled() {
        Map<String, Object> out = gateway.refund("CAP-1", 0L);
        assertThat(out).containsEntry("status", "COMPLETED").containsEntry("mock", true);
    }

    @Test
    void refundFullSendsEmptyBody() {
        enable();
        Map<String, Object> tokenResp = Map.of("access_token", "tok");
        Map<String, Object> refundResp = Map.of("id", "RE-1", "status", "COMPLETED");
        enqueueResponses(tokenResp, refundResp);

        Map<String, Object> out = gateway.refund("CAP-1", 0L);

        assertThat(out).containsEntry("status", "COMPLETED");
        verify(uriSpec).uri("https://paypal.test/v2/payments/captures/CAP-1/refund");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec, Mockito.atLeastOnce()).bodyValue(bodyCaptor.capture());
        // Full refund -> the operation body (last bodyValue) is an empty map (no amount).
        Object lastBody = bodyCaptor.getAllValues().get(bodyCaptor.getAllValues().size() - 1);
        assertThat(lastBody).isEqualTo(Map.of());
    }

    @Test
    void refundPartialConvertsAmountToUsdValue() {
        enable();
        Map<String, Object> tokenResp = Map.of("access_token", "tok");
        Map<String, Object> refundResp = Map.of("id", "RE-2", "status", "COMPLETED");
        enqueueResponses(tokenResp, refundResp);

        // 2599 cents -> 25.99 USD
        gateway.refund("CAP-2", 2_599L);

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(bodySpec, Mockito.atLeastOnce()).bodyValue(bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> refundBody = bodyCaptor.getAllValues().stream()
                .filter(b -> b instanceof Map && ((Map<?, ?>) b).containsKey("amount")).map(b -> (Map<String, Object>) b)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) refundBody.get("amount");
        assertThat(amount).containsEntry("currency_code", "USD").containsEntry("value", "25.99");
    }

    // ---------------------------------------------------------------- confirm (pure logic)

    @Test
    void confirmCompletedCaseInsensitive() {
        assertThat(gateway.confirm(null, Map.of("status", "COMPLETED")).succeeded()).isTrue();
        assertThat(gateway.confirm(null, Map.of("status", "completed")).succeeded()).isTrue();
    }

    @Test
    void confirmFailsForOtherStatus() {
        ConfirmResult r = gateway.confirm(null, Map.of("status", "PENDING"));
        assertThat(r.succeeded()).isFalse();
        assertThat(r.errorMessage()).isEqualTo("PayPal status: PENDING");
    }

    // ---------------------------------------------------------------- extractCaptureId (pure)

    @Test
    void extractCaptureIdHappyPath() {
        Map<String, Object> resp = Map.of("purchase_units",
                List.of(Map.of("payments", Map.of("captures", List.of(Map.of("id", "CAPTURE-99"))))));
        assertThat(PayPalGateway.extractCaptureId(resp)).isEqualTo("CAPTURE-99");
    }

    @Test
    void extractCaptureIdReturnsNullForMalformedOrNull() {
        assertThat(PayPalGateway.extractCaptureId(null)).isNull();
        assertThat(PayPalGateway.extractCaptureId(Map.of())).isNull();
        assertThat(PayPalGateway.extractCaptureId(Map.of("purchase_units", List.of()))).isNull();
        assertThat(PayPalGateway.extractCaptureId(Map.of("purchase_units", List.of(Map.of("payments", Map.of())))))
                .isNull();
    }
}
