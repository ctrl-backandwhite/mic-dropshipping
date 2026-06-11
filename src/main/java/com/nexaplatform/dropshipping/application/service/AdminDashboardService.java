package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardMetricsDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardSeriesDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminDashboardMapper;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
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
 * Use-case service that aggregates admin dashboard data (KPI metrics, time-series
 * buckets and recent orders) from the various repositories. Holds all the logic
 * previously inlined in {@code AdminDashboardController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;
    private final SubscriptionPlanRepository planRepository;
    private final CustomerSubscriptionRepository subscriptionRepository;
    private final PricingService pricingService;
    private final AdminDashboardMapper adminDashboardMapper;

    @Transactional(readOnly = true)
    public AdminDashboardMetricsDtoOut metrics() {
        long activeProducts = productRepository.findByStatus(ProductStatus.ACTIVE, PageRequest.of(0, 1)).getTotalElements();
        long allProducts = productRepository.count();
        long draftProducts = Math.max(0, allProducts - activeProducts);
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

        return AdminDashboardMetricsDtoOut.builder()
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

    @Transactional(readOnly = true)
    public AdminDashboardSeriesDtoOut series() {
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
        return AdminDashboardSeriesDtoOut.builder()
                .ordersByDay(ordersByDay)
                .gmvCentsByDay(gmvByDay)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AdminDashboardRecentOrderDtoOut> recentOrders() {
        List<CustomerOrderEntity> recent = orderRepository.findAll().stream()
                .sorted((a, b) -> {
                    Instant ai = a.getPlacedAt() != null ? a.getPlacedAt() : a.getCreatedAt();
                    Instant bi = b.getPlacedAt() != null ? b.getPlacedAt() : b.getCreatedAt();
                    return bi.compareTo(ai);
                })
                .limit(10)
                .toList();
        return adminDashboardMapper.toRecentOrderDtos(recent);
    }
}
