package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.usecase.impl.AdminDashboardUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.DashboardRecentOrder;
import com.nexaplatform.dropshipping.domain.model.DashboardSeries;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardUseCaseImplTest {

    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock UserRepository userRepository;
    @Mock SubscriptionPlanRepository planRepository;
    @Mock CustomerSubscriptionRepository subscriptionRepository;
    @Mock PricingService pricingService;

    @InjectMocks AdminDashboardUseCaseImpl useCase;

    @Test
    void series_bucketsRecentOrdersByDay() {
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        String day = recent.toString().substring(0, 10);
        var order = CustomerOrderEntity.builder().placedAt(recent).totalCents(500).build();
        var stale = CustomerOrderEntity.builder()
                .placedAt(Instant.now().minus(90, ChronoUnit.DAYS)).totalCents(999).build();
        when(orderRepository.findAll()).thenReturn(List.of(order, stale));

        DashboardSeries result = useCase.series();

        assertThat(result.getOrdersByDay()).containsEntry(day, 1L);
        assertThat(result.getGmvCentsByDay()).containsEntry(day, 500L);
    }

    @Test
    void recentOrders_sortsByPlacedAtDescAndProjectsToModel() {
        var older = CustomerOrderEntity.builder()
                .orderNumber("OLD").placedAt(Instant.now().minus(5, ChronoUnit.DAYS)).totalCents(100).build();
        var newer = CustomerOrderEntity.builder()
                .orderNumber("NEW").placedAt(Instant.now().minus(1, ChronoUnit.DAYS)).totalCents(200).build();
        when(orderRepository.findAll()).thenReturn(List.of(older, newer));

        List<DashboardRecentOrder> result = useCase.recentOrders();

        assertThat(result).extracting(DashboardRecentOrder::getOrderNumber).containsExactly("NEW", "OLD");
        assertThat(result.get(0).getTotalCents()).isEqualTo(200);
    }
}
