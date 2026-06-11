package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.usecase.AdminDashboardUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.DashboardMetrics;
import com.nexaplatform.dropshipping.domain.model.DashboardRecentOrder;
import com.nexaplatform.dropshipping.domain.model.DashboardSeries;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-dashboard use case. Aggregates KPI metrics, time-series buckets and recent
 * orders from the various repositories and returns domain projection models. Holds
 * all the logic previously inlined in the dashboard controller/service. Pure reads,
 * so the existing Spring Data repositories are injected directly as read
 * collaborators (no domain port).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardUseCaseImpl implements AdminDashboardUseCase {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository planRepository;
    private final CustomerSubscriptionRepository subscriptionRepository;
    private final PricingService pricingService;

    @Override
    @Transactional(readOnly = true)
    public DashboardMetrics metrics() {
        long activeProducts = productRepository.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 1)).getTotalElements();
        long allProducts = productRepository.count();
        long totalOrders = orderRepository.count();
        long totalUsers = userRepository.count();
        long totalSuppliers = supplierRepository.count();
        long activePlans = planRepository.findByActiveTrueOrderByPositionAsc().size();
        long totalSubs = subscriptionRepository.count();

        // GMV from order subtotals (in cents -> USD; if not in USD, leave as cents/100)
        long gmvCents = orderRepository.findAll().stream().mapToLong(CustomerOrderEntity::getTotalCents).sum();
        BigDecimal gmvUsd = BigDecimal.valueOf(gmvCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal gmvDisplay = pricingService.convertUsdToDisplay(gmvUsd);

        // MRR estimate: sum of plan monthly prices for ACTIVE subscriptions on MONTHLY billing
        long mrrCents = subscriptionRepository.findAll().stream()
                .filter(s -> "ACTIVE".equals(s.getStatus().name()))
                .filter(s -> !"YEARLY".equalsIgnoreCase(s.getBillingPeriod()))
                .mapToLong(s -> s.getPlan().getPriceMonthlyCents()).sum();
        BigDecimal mrrUsd = BigDecimal.valueOf(mrrCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        return DashboardMetrics.builder()
                .activeProducts(activeProducts)
                .totalProducts(allProducts)
                .draftProducts(allProducts - activeProducts)
                .totalOrders(totalOrders)
                .totalUsers(totalUsers)
                .totalSuppliers(totalSuppliers)
                .activePlans(activePlans)
                .totalSubscriptions(totalSubs)
                .gmvUsd(gmvUsd)
                .gmvDisplay(gmvDisplay)
                .mrrUsd(mrrUsd)
                .displayCurrency(pricingService.displayCurrencyCode())
                .displaySymbol(pricingService.displayCurrencySymbol())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardSeries series() {
        // Bucket orders by day for the last 30 days
        Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
        List<CustomerOrderEntity> orders = orderRepository.findAll().stream()
                .filter(o -> o.getPlacedAt() != null && o.getPlacedAt().isAfter(from))
                .toList();
        Map<String, Long> ordersByDay = new HashMap<>();
        Map<String, Long> gmvByDay = new HashMap<>();
        for (CustomerOrderEntity o : orders) {
            String day = o.getPlacedAt().toString().substring(0, 10);
            ordersByDay.merge(day, 1L, Long::sum);
            gmvByDay.merge(day, (long) o.getTotalCents(), Long::sum);
        }
        return DashboardSeries.builder()
                .ordersByDay(ordersByDay)
                .gmvCentsByDay(gmvByDay)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DashboardRecentOrder> recentOrders() {
        return orderRepository.findAll().stream()
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                })
                .limit(10)
                .map(this::toRecentOrder)
                .toList();
    }

    /** Projects a persisted order entity into the read-only recent-order model. */
    private DashboardRecentOrder toRecentOrder(CustomerOrderEntity entity) {
        return DashboardRecentOrder.builder()
                .id(entity.getId())
                .orderNumber(entity.getOrderNumber())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .totalCents(entity.getTotalCents())
                .currency(entity.getCurrency())
                .placedAt(entity.getPlacedAt())
                .build();
    }
}
