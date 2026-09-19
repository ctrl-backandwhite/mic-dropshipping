package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.OrderPaymentDtoMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.RechargeOptions;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentUseCaseImplTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService stripeService;
    @Mock
    AuditLogger auditLogger;
    @Mock
    PartnerPlanSyncService partnerPlanSyncService;
    @Mock
    com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase customerSubscriptionUseCase;
    @Mock
    com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService subscriptionNotificationService;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    CurrencyRateService currencyRateService;
    @Mock
    com.nexaplatform.dropshipping.application.service.StockService stockService;

    private final OrderPaymentDtoMapper orderPaymentDtoMapper = Mappers.getMapper(OrderPaymentDtoMapper.class);

    private PaymentUseCaseImpl useCase() {
        return new PaymentUseCaseImpl(List.<PaymentGateway>of(), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, stripeService, auditLogger, partnerPlanSyncService,
                customerSubscriptionUseCase, subscriptionNotificationService, new ObjectMapper(), orderEmailService,
                currencyRateService, new OrderAmounts(currencyRateService), stockService, mock(SupplierPurchaseService.class), mock(OpsAlertService.class), mock(CartService.class));
    }

    @Test
    void orderPaymentMapper_projectsProviderMetadata() {
        Payment p = Payment.builder().method(PaymentMethod.CARD).status(PaymentStatus.REQUIRES_ACTION)
                .amountUsdCents(5000).provider("stripe").providerRef("pi_1")
                .providerResponse(Map.of("clientSecret", "cs_test", "approveUrl", "https://x")).build();
        p.setId(UUID.randomUUID());

        OrderPaymentDtoOut view = orderPaymentDtoMapper.toDtoOut(p);

        assertThat(view.getClientSecret()).isEqualTo("cs_test");
        assertThat(view.getApproveUrl()).isEqualTo("https://x");
        assertThat(view.getMethod()).isEqualTo("CARD");
    }

    @Test
    void getOrderPayment_rejectsMismatchedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Payment p = Payment.builder().method(PaymentMethod.CARD).status(PaymentStatus.PENDING).amountUsdCents(100)
                .orderId(UUID.randomUUID()).build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        PaymentUseCaseImpl svc = useCase();

        assertThatThrownBy(() -> svc.getOrderPayment(orderId, paymentId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rechargeOptions_standardCurrency_keepsBaseAmounts() {
        when(currencyRateService.symbolOf("EUR")).thenReturn("€");
        when(currencyRateService.formatDisplay(any(), eq("EUR")))
                .thenAnswer(inv -> inv.getArgument(0) + " €");

        RechargeOptions opts = useCase().rechargeOptions("eur");

        assertThat(opts.currency()).isEqualTo("EUR");
        assertThat(opts.symbol()).isEqualTo("€");
        // EUR conserva los importes estándar (no se convierten): 10/25/50/100/250/500.
        assertThat(opts.presets()).extracting(p -> p.amount().intValueExact())
                .containsExactly(10, 25, 50, 100, 250, 500);
    }

    @Test
    void rechargeOptions_foreignCurrency_convertsAndRoundsToNiceAmounts() {
        // 1 USD = 4123,45 COP → los presets se convierten y se REDONDEAN a 2 cifras significativas.
        when(currencyRateService.symbolOf("COP")).thenReturn("$");
        when(currencyRateService.usdTo(any(), eq("COP")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("4123.45")));
        lenient().when(currencyRateService.decimalsOf(anyString())).thenReturn(2);
        when(currencyRateService.formatDisplay(any(), eq("COP")))
                .thenAnswer(inv -> inv.getArgument(0) + " COP");

        RechargeOptions opts = useCase().rechargeOptions("COP");

        // $10→41 234,5→41 000; $25→103 086→100 000; $50→206 172→210 000; $100→412 345→410 000;
        // $250→1 030 862→1 000 000; $500→2 061 725→2 100 000. Todos redondos, sin decimales sucios.
        assertThat(opts.presets()).extracting(p -> p.amount().longValueExact())
                .containsExactly(41000L, 100000L, 210000L, 410000L, 1000000L, 2100000L);
    }

    @Test
    void handleStripeEvent_subscriptionForwardsToPlanSync() {
        String payload = "{\"data\":{\"object\":{\"id\":\"sub_123\",\"status\":\"active\"}}}";

        String body = useCase().handleStripeEvent("customer.subscription.updated", payload);

        assertThat(body).isEqualTo("ok");
        verify(partnerPlanSyncService).onSubscriptionEvent("sub_123", "active",
                "customer.subscription.updated");
    }

    @Test
    void payOrderWithSavedCard_stripeDisabled_throws() {
        when(stripeService.isEnabled()).thenReturn(false);
        PaymentUseCaseImpl svc = useCase();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> svc.payOrderWithSavedCard(userId, orderId, "pm_x", "idem-1"))
                .isInstanceOf(com.nexaplatform.dropshipping.api.exception.BusinessException.class);
    }

    @Test
    void payOrderWithSavedCard_cardNotOwned_throwsNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(stripeService.isEnabled()).thenReturn(true);
        when(paymentRepository.findByIdempotencyKey("idem-2")).thenReturn(Optional.empty());
        com.nexaplatform.dropshipping.domain.model.Order order = com.nexaplatform.dropshipping.domain.model.Order
                .builder().userId(userId).status(com.nexaplatform.dropshipping.domain.enums.OrderStatus.AWAITING_PAYMENT)
                .totalCents(5000).build();
        order.setId(orderId);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity user =
                new com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity();
        user.setStripeCustomerId("cus_1");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // El Customer tiene OTRA tarjeta, no la pm_hack solicitada → IDOR bloqueado.
        com.stripe.model.PaymentMethod other = new com.stripe.model.PaymentMethod();
        other.setId("pm_legit");
        when(stripeService.listCards("cus_1")).thenReturn(List.of(other));
        PaymentUseCaseImpl svc = useCase();

        assertThatThrownBy(() -> svc.payOrderWithSavedCard(userId, orderId, "pm_hack", "idem-2"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmSavedCardPayment_mismatchedUser_throwsNotFound() {
        UUID paymentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Payment p = Payment.builder().userId(UUID.randomUUID()).method(PaymentMethod.CARD)
                .status(PaymentStatus.PENDING).amountUsdCents(5000).orderId(orderId).build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        PaymentUseCaseImpl svc = useCase();

        assertThatThrownBy(() -> svc.confirmSavedCardPayment(UUID.randomUUID(), orderId, paymentId))
                .isInstanceOf(NotFoundException.class);
    }
}
