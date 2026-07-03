package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway.ConfirmResult;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway.InitiateResult;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StripeGatewayTest {

    private StripeGateway gateway;

    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        gateway = new StripeGateway();
        ReflectionTestUtils.setField(gateway, "secretKey", "sk_test_123");
        ReflectionTestUtils.setField(gateway, "platformId", "nexadrop-test");
        ReflectionTestUtils.setField(gateway, "platformEnv", "test");
        ReflectionTestUtils.setField(gateway, "storefrontBaseUrl", "https://shop.test");
    }

    private void enable() {
        ReflectionTestUtils.setField(gateway, "enabled", true);
    }

    private PaymentEntity payment(UUID orderId, String settlementCcy, BigDecimal settlementAmount, long usdCents) {
        UserEntity user = mock(UserEntity.class);
        when(user.getEmail()).thenReturn("buyer@test.com");
        when(user.getId()).thenReturn(USER_ID);

        PaymentEntity p = mock(PaymentEntity.class);
        when(p.getId()).thenReturn(PAYMENT_ID);
        when(p.getOrderId()).thenReturn(orderId);
        when(p.getUser()).thenReturn(user);
        when(p.getSettlementCurrency()).thenReturn(settlementCcy);
        when(p.getSettlementAmount()).thenReturn(settlementAmount);
        when(p.getAmountUsdCents()).thenReturn(usdCents);
        return p;
    }

    // ---------------------------------------------------------------- supports / providerName

    @Test
    void supportsOnlyCard() {
        assertThat(gateway.supports(PaymentMethod.CARD)).isTrue();
        assertThat(gateway.supports(PaymentMethod.PAYPAL)).isFalse();
        assertThat(gateway.providerName()).isEqualTo("stripe");
    }

    // ---------------------------------------------------------------- mock mode (disabled)

    @Test
    void initiateMockModeForOrderCheckout() {
        PaymentEntity p = payment(ORDER_ID, "USD", null, 5_000L);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("cs_mock_" + PAYMENT_ID);
        assertThat(r.approveUrl()).contains("/checkout/return").contains("orderId=" + ORDER_ID)
                .contains("paymentId=" + PAYMENT_ID).contains("mock=1");
        assertThat(r.clientSecret()).isNull();
        assertThat(r.raw()).containsEntry("mock", true);
    }

    @Test
    void initiateMockModeForWalletRecharge() {
        // La recarga de wallet usa el MISMO Checkout hospedado que los pedidos (mock → cs_mock_ + redirect).
        PaymentEntity p = payment(null, "USD", null, 5_000L);

        InitiateResult r = gateway.initiate(p);

        assertThat(r.providerRef()).isEqualTo("cs_mock_" + PAYMENT_ID);
        assertThat(r.clientSecret()).isNull();
        assertThat(r.approveUrl()).contains("/wallet/recharge/return");
    }

    // ---------------------------------------------------------------- checkout session (order)

    @Test
    void initiateCheckoutSessionMapsResponse() throws Exception {
        enable();
        PaymentEntity p = payment(ORDER_ID, "USD", new BigDecimal("49.99"), 4_999L);

        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_test_abc");
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/abc");
        when(session.getPaymentIntent()).thenReturn("pi_abc");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.create(any(SessionCreateParams.class))).thenReturn(session);

            InitiateResult r = gateway.initiate(p);

            assertThat(r.providerRef()).isEqualTo("cs_test_abc");
            assertThat(r.approveUrl()).isEqualTo("https://checkout.stripe.com/abc");
            assertThat(r.clientSecret()).isNull();
            assertThat(r.raw()).containsEntry("id", "cs_test_abc")
                    .containsEntry("approveUrl", "https://checkout.stripe.com/abc")
                    .containsEntry("paymentIntent", "pi_abc");
        }
    }

    @Test
    void initiateCheckoutSessionUsesEurSettlementCentsExact() throws Exception {
        enable();
        // 49.99 EUR -> 4999 cents (exact rounding from settlementAmount, NOT amountUsdCents=9999)
        PaymentEntity p = payment(ORDER_ID, "EUR", new BigDecimal("49.99"), 9_999L);

        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_eur");
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/eur");
        when(session.getPaymentIntent()).thenReturn("pi_eur");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            SessionCreateParams[] captured = new SessionCreateParams[1];
            sessions.when(() -> Session.create(any(SessionCreateParams.class))).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return session;
            });

            gateway.initiate(p);

            SessionCreateParams params = captured[0];
            SessionCreateParams.LineItem item = params.getLineItems().get(0);
            assertThat(item.getPriceData().getCurrency()).isEqualTo("eur");
            assertThat(item.getPriceData().getUnitAmount()).isEqualTo(4_999L);
        }
    }

    @Test
    void initiateCheckoutSessionFallsBackToUsdCentsWhenNoSettlementAmount() throws Exception {
        enable();
        PaymentEntity p = payment(ORDER_ID, "USD", null, 7_777L);

        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_usd");
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/usd");
        when(session.getPaymentIntent()).thenReturn("pi_usd");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            SessionCreateParams[] captured = new SessionCreateParams[1];
            sessions.when(() -> Session.create(any(SessionCreateParams.class))).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return session;
            });

            gateway.initiate(p);

            SessionCreateParams.LineItem item = captured[0].getLineItems().get(0);
            assertThat(item.getPriceData().getCurrency()).isEqualTo("usd");
            assertThat(item.getPriceData().getUnitAmount()).isEqualTo(7_777L);
        }
    }

    // ---------------------------------------------------------------- checkout session (wallet recharge)

    @Test
    void initiateCheckoutSessionForWalletRecharge() throws Exception {
        // La recarga (sin orderId) usa el mismo Checkout hospedado que los pedidos (antes era PaymentIntent).
        enable();
        PaymentEntity p = payment(null, "USD", null, 2_500L);

        Session session = mock(Session.class);
        when(session.getId()).thenReturn("cs_wallet");
        when(session.getUrl()).thenReturn("https://checkout.stripe.com/wallet");
        when(session.getPaymentIntent()).thenReturn("pi_w");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            SessionCreateParams[] captured = new SessionCreateParams[1];
            sessions.when(() -> Session.create(any(SessionCreateParams.class))).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return session;
            });

            InitiateResult r = gateway.initiate(p);

            assertThat(r.providerRef()).isEqualTo("cs_wallet");
            assertThat(r.approveUrl()).isEqualTo("https://checkout.stripe.com/wallet");
            assertThat(r.clientSecret()).isNull();
        }
    }

    // ---------------------------------------------------------------- retrieveCheckoutSession

    @Test
    void retrieveCheckoutSessionMockWhenDisabled() {
        Map<String, Object> out = gateway.retrieveCheckoutSession("cs_x");
        assertThat(out).containsEntry("status", "paid").containsEntry("mock", true);
    }

    @Test
    void retrieveCheckoutSessionPaid() throws Exception {
        enable();
        Session session = mock(Session.class);
        when(session.getPaymentStatus()).thenReturn("paid");
        when(session.getPaymentIntent()).thenReturn("pi_done");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.retrieve("cs_paid")).thenReturn(session);

            Map<String, Object> out = gateway.retrieveCheckoutSession("cs_paid");

            assertThat(out).containsEntry("status", "paid").containsEntry("payment_status", "paid")
                    .containsEntry("paymentIntent", "pi_done");
        }
    }

    @Test
    void retrieveCheckoutSessionUnpaidPassesThroughStatus() throws Exception {
        enable();
        Session session = mock(Session.class);
        when(session.getPaymentStatus()).thenReturn("unpaid");
        when(session.getPaymentIntent()).thenReturn("pi_pending");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.retrieve("cs_unpaid")).thenReturn(session);

            Map<String, Object> out = gateway.retrieveCheckoutSession("cs_unpaid");

            assertThat(out).containsEntry("status", "unpaid").containsEntry("payment_status", "unpaid");
        }
    }

    // ---------------------------------------------------------------- refund

    @Test
    void refundMockWhenDisabled() {
        Map<String, Object> out = gateway.refund("pi_x", 0L);
        assertThat(out).containsEntry("status", "succeeded").containsEntry("mock", true);
    }

    @Test
    void refundFull(/* amount<=0 -> no amount set */) throws Exception {
        enable();
        Refund refund = mock(Refund.class);
        when(refund.getId()).thenReturn("re_1");
        when(refund.getStatus()).thenReturn("succeeded");

        try (MockedStatic<Refund> refunds = mockStatic(Refund.class)) {
            RefundCreateParams[] captured = new RefundCreateParams[1];
            refunds.when(() -> Refund.create(any(RefundCreateParams.class))).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return refund;
            });

            Map<String, Object> out = gateway.refund("pi_full", 0L);

            assertThat(out).containsEntry("id", "re_1").containsEntry("status", "succeeded");
            assertThat(captured[0].getPaymentIntent()).isEqualTo("pi_full");
            assertThat(captured[0].getAmount()).isNull();
        }
    }

    @Test
    void refundPartialSetsAmount() throws Exception {
        enable();
        Refund refund = mock(Refund.class);
        when(refund.getId()).thenReturn("re_2");
        when(refund.getStatus()).thenReturn("pending");

        try (MockedStatic<Refund> refunds = mockStatic(Refund.class)) {
            RefundCreateParams[] captured = new RefundCreateParams[1];
            refunds.when(() -> Refund.create(any(RefundCreateParams.class))).thenAnswer(inv -> {
                captured[0] = inv.getArgument(0);
                return refund;
            });

            Map<String, Object> out = gateway.refund("pi_part", 1_234L);

            assertThat(out).containsEntry("status", "pending");
            assertThat(captured[0].getAmount()).isEqualTo(1_234L);
        }
    }

    // ---------------------------------------------------------------- confirm

    @Test
    void confirmSucceededStatus() {
        ConfirmResult r = gateway.confirm(null, Map.of("status", "succeeded"));
        assertThat(r.succeeded()).isTrue();
        assertThat(r.errorMessage()).isNull();
    }

    @Test
    void confirmPaidStatus() {
        ConfirmResult r = gateway.confirm(null, Map.of("status", "paid"));
        assertThat(r.succeeded()).isTrue();
    }

    @Test
    void confirmFailsForOtherStatus() {
        ConfirmResult r = gateway.confirm(null, Map.of("status", "unpaid"));
        assertThat(r.succeeded()).isFalse();
        assertThat(r.errorMessage()).isEqualTo("Stripe status: unpaid");
    }
}
