package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.PaymentService;
import com.nexaplatform.dropshipping.application.service.WalletService;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class PaymentServiceOrderTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock OrderRepository orderRepository;
    @Mock WalletService walletService;
    @Mock AuditLogger auditLogger;
    @Mock PartnerPlanSyncService partnerPlanSyncService;
    @Mock MeWalletDtoMapper meWalletDtoMapper;

    private PaymentService service() {
        return new PaymentService(List.<PaymentGateway>of(), paymentRepository, userRepository, orderRepository,
                walletService, auditLogger, partnerPlanSyncService, meWalletDtoMapper, new ObjectMapper());
    }

    @Test
    void toPaymentView_projectsProviderMetadata() {
        PaymentEntity p = PaymentEntity.builder()
                .method(PaymentMethod.CARD).status(PaymentStatus.REQUIRES_ACTION)
                .amountUsdCents(5000).provider("stripe").providerRef("pi_1")
                .providerResponse(Map.of("clientSecret", "cs_test", "approveUrl", "https://x"))
                .build();
        p.setId(UUID.randomUUID());

        OrderPaymentDtoOut view = service().toPaymentView(p);

        assertThat(view.getClientSecret()).isEqualTo("cs_test");
        assertThat(view.getApproveUrl()).isEqualTo("https://x");
        assertThat(view.getMethod()).isEqualTo("CARD");
    }

    @Test
    void getOrderPayment_rejectsMismatchedOrder() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = PaymentEntity.builder()
                .method(PaymentMethod.CARD).status(PaymentStatus.PENDING).amountUsdCents(100)
                .orderId(UUID.randomUUID()).build();
        p.setId(paymentId);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        PaymentService svc = service();

        assertThatThrownBy(() -> svc.getOrderPayment(orderId, paymentId))
                .isInstanceOf(com.nexaplatform.dropshipping.api.exception.NotFoundException.class);
    }

    @Test
    void handleStripeEvent_subscriptionForwardsToPlanSync() {
        String payload = "{\"data\":{\"object\":{\"id\":\"sub_123\",\"status\":\"active\"}}}";

        String body = service().handleStripeEvent("customer.subscription.updated", payload);

        assertThat(body).isEqualTo("ok");
        org.mockito.Mockito.verify(partnerPlanSyncService)
                .onSubscriptionEvent("sub_123", "active", "customer.subscription.updated");
    }
}
