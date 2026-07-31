package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OperatorActionIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OperatorOrderActionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperatorCommissionServiceTest {

    @Mock
    OperatorOrderActionRepository actionRepository;
    @Mock
    OperatorActionIndexer indexer;
    @Mock
    UserRepository userRepository;
    @InjectMocks
    OperatorCommissionService service;

    private static Order order(String source, OrderItem... items) {
        return Order.builder().id(UUID.randomUUID()).orderNumber("NX-1").source(source)
                .items(List.of(items)).build();
    }

    private static OrderItem item(long costCnyCents, int qty) {
        return OrderItem.builder().costCnyCents(costCnyCents).quantity(qty).build();
    }

    @Test
    void recordDelivery_noOpWhenNoAuthenticatedSubject() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(null);
            service.recordDelivery(order("PLATFORM", item(11300, 1)));
            verify(actionRepository, never()).save(any());
        }
    }

    @Test
    void recordDelivery_idempotentWhenAlreadyCredited() {
        UUID subject = UUID.randomUUID();
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(subject.toString());
            Order o = order("PLATFORM", item(11300, 1));
            when(actionRepository.existsByOrderIdAndAction(o.getId(), "DELIVERED")).thenReturn(true);

            service.recordDelivery(o);

            verify(actionRepository, never()).save(any());
        }
    }

    @Test
    void recordDelivery_platformCredits10PctOnCnyBaseExVat() {
        UUID subject = UUID.randomUUID();
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(subject.toString());
            when(userRepository.findById(subject))
                    .thenReturn(Optional.of(UserEntity.builder().email("ops@nx036.local").displayName("Carlos").build()));
            when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // 11300 * 2 = 22600 gross; base = 22600/1.13 = 20000; 10% = 2000 cents
            service.recordDelivery(order("PLATFORM", item(11300, 2)));

            ArgumentCaptor<OperatorOrderActionEntity> captor = ArgumentCaptor.forClass(OperatorOrderActionEntity.class);
            verify(actionRepository).save(captor.capture());
            OperatorOrderActionEntity a = captor.getValue();
            assertThat(a.getCommissionCnyCents()).isEqualTo(2000);
            assertThat(a.getItemCount()).isEqualTo(2);
            assertThat(a.getOrderSource()).isEqualTo("PLATFORM");
            assertThat(a.getAction()).isEqualTo("DELIVERED");
            assertThat(a.getOperatorEmail()).isEqualTo("ops@nx036.local");
            verify(indexer).index(a);
        }
    }

    @Test
    void recordDelivery_integrationCredits5Pct() {
        UUID subject = UUID.randomUUID();
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(subject.toString());
            when(userRepository.findById(any())).thenReturn(Optional.empty());
            when(actionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // 11300 gross; base = 10000; 5% = 500 cents
            service.recordDelivery(order("INTEGRATION", item(11300, 1)));

            ArgumentCaptor<OperatorOrderActionEntity> captor = ArgumentCaptor.forClass(OperatorOrderActionEntity.class);
            verify(actionRepository).save(captor.capture());
            OperatorOrderActionEntity a = captor.getValue();
            assertThat(a.getCommissionCnyCents()).isEqualTo(500);
            assertThat(a.getOrderSource()).isEqualTo("INTEGRATION");
        }
    }
}
