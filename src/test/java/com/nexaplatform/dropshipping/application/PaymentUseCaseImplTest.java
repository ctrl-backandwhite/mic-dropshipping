package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.mapper.OrderPaymentDtoMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentUseCaseImplTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock UserRepository userRepository;
    @Mock OrderRepository orderRepository;
    @Mock WalletUseCase walletUseCase;
    @Mock AuditLogger auditLogger;
    @Mock PartnerPlanSyncService partnerPlanSyncService;

    private final OrderPaymentDtoMapper orderPaymentDtoMapper = Mappers.getMapper(OrderPaymentDtoMapper.class);

    private PaymentUseCaseImpl useCase() {
        return new PaymentUseCaseImpl(List.<PaymentGateway>of(), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, auditLogger, partnerPlanSyncService, new ObjectMapper());
    }

    @Test
    void orderPaymentMapper_projectsProviderMetadata() {
        Payment p = Payment.builder()
                .method(PaymentMethod.CARD).status(PaymentStatus.REQUIRES_ACTION)
                .amountUsdCents(5000).provider("stripe").providerRef("pi_1")
                .providerResponse(Map.of("clientSecret", "cs_test", "approveUrl", "https://x"))
                .build();
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
        Payment p = Payment.builder()
                .method(PaymentMethod.CARD).status(PaymentStatus.PENDING).amountUsdCents(100)
                .orderId(UUID.randomUUID()).build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        PaymentUseCaseImpl svc = useCase();

        assertThatThrownBy(() -> svc.getOrderPayment(orderId, paymentId))
                .isInstanceOf(com.nexaplatform.dropshipping.api.exception.NotFoundException.class);
    }

    @Test
    void handleStripeEvent_subscriptionForwardsToPlanSync() {
        String payload = "{\"data\":{\"object\":{\"id\":\"sub_123\",\"status\":\"active\"}}}";

        String body = useCase().handleStripeEvent("customer.subscription.updated", payload);

        assertThat(body).isEqualTo("ok");
        org.mockito.Mockito.verify(partnerPlanSyncService)
                .onSubscriptionEvent("sub_123", "active", "customer.subscription.updated");
    }
}
