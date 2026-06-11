package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminDashboardMapper;
import com.nexaplatform.dropshipping.application.service.AdminDashboardService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    @Mock ProductRepository productRepository;
    @Mock OrderRepository orderRepository;
    @Mock SupplierRepository supplierRepository;
    @Mock UserRepository userRepository;
    @Mock SubscriptionPlanRepository planRepository;
    @Mock CustomerSubscriptionRepository subscriptionRepository;
    @Mock PricingService pricingService;
    @Mock AdminDashboardMapper adminDashboardMapper;

    @Captor ArgumentCaptor<List<CustomerOrderEntity>> ordersCaptor;

    private AdminDashboardService service() {
        return new AdminDashboardService(productRepository, orderRepository, supplierRepository,
                userRepository, planRepository, subscriptionRepository, pricingService, adminDashboardMapper);
    }

    @Test
    void series_bucketsRecentOrdersByDay() {
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        String day = recent.toString().substring(0, 10);
        var order = CustomerOrderEntity.builder().placedAt(recent).totalCents(500).build();
        var stale = CustomerOrderEntity.builder()
                .placedAt(Instant.now().minus(90, ChronoUnit.DAYS)).totalCents(999).build();
        when(orderRepository.findAll()).thenReturn(List.of(order, stale));

        AdminDashboardSeriesDtoOut result = service().series();

        assertThat(result.getOrdersByDay()).containsEntry(day, 1L);
        assertThat(result.getGmvCentsByDay()).containsEntry(day, 500L);
    }

    @Test
    void recentOrders_sortsByPlacedAtDescAndDelegatesToMapper() {
        var older = CustomerOrderEntity.builder().placedAt(Instant.now().minus(5, ChronoUnit.DAYS)).build();
        var newer = CustomerOrderEntity.builder().placedAt(Instant.now().minus(1, ChronoUnit.DAYS)).build();
        when(orderRepository.findAll()).thenReturn(List.of(older, newer));
        when(adminDashboardMapper.toRecentOrderDtos(any())).thenReturn(List.of());

        service().recentOrders();

        verify(adminDashboardMapper).toRecentOrderDtos(ordersCaptor.capture());
        assertThat(ordersCaptor.getValue()).containsExactly(newer, older);
    }
}
