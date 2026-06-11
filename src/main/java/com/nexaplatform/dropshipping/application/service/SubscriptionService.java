package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.BillingDtos.SubscriptionView;
import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SubscribeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminBillingMapper;
import com.nexaplatform.dropshipping.api.mapper.BillingDtoMapper;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final CustomerSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AdminBillingMapper adminBillingMapper;
    private final BillingDtoMapper billingDtoMapper;
    private final StripeService stripeService;

    @Transactional(readOnly = true)
    public List<AdminSubscriptionDtoOut> listAdminSubscriptions(String status) {
        List<CustomerSubscriptionEntity> subscriptions = subscriptionRepository.findAll().stream()
                .filter(s -> status == null || status.isBlank() || s.getStatus().name().equalsIgnoreCase(status))
                .toList();
        return adminBillingMapper.toSubscriptionDtos(subscriptions);
    }

    @Transactional(readOnly = true)
    public List<AdminPlanDtoOut> listAdminPlans() {
        List<SubscriptionPlanEntity> plans = planRepository.findAll().stream()
                .sorted(java.util.Comparator.comparingInt(SubscriptionPlanEntity::getPosition))
                .toList();
        return adminBillingMapper.toPlanDtos(plans);
    }

    @Transactional
    public OperationResponseDtoOut updatePlan(String code, AdminPlanUpdateDtoIn dto) {
        SubscriptionPlanEntity plan = getPlanByCode(code);
        adminBillingMapper.updatePlanFromDto(dto, plan);
        planRepository.save(plan);
        log.info("::> [BILLING] Plan updated code={}", code);
        return OperationResponseDtoOut.ok("Plan updated");
    }

    /** Active public plans ordered by position, projected to the storefront DtoOut. */
    @Transactional(readOnly = true)
    public List<BillingPlanDtoOut> listPublicPlanDtos() {
        return billingDtoMapper.toPlanDtoOutList(planRepository.findByActiveTrueOrderByPositionAsc());
    }

    @Transactional(readOnly = true)
    public SubscriptionPlanEntity getPlanByCode(String code) {
        return planRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Plan not found: " + code));
    }

    @Transactional
    public CustomerSubscriptionEntity createSubscription(UUID userId, String planCode, String billingPeriod) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        SubscriptionPlanEntity plan = getPlanByCode(planCode);
        Instant now = Instant.now();
        Instant end = "YEARLY".equalsIgnoreCase(billingPeriod) ? now.plus(365, ChronoUnit.DAYS) : now.plus(30, ChronoUnit.DAYS);
        CustomerSubscriptionEntity sub = CustomerSubscriptionEntity.builder()
                .user(user)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .billingPeriod(billingPeriod == null ? "MONTHLY" : billingPeriod.toUpperCase())
                .currentPeriodStart(now)
                .currentPeriodEnd(end)
                .build();
        return subscriptionRepository.save(sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> listForUser(UUID userId) {
        return subscriptionRepository.findByUserId(userId).stream()
                .map(s -> new SubscriptionView(
                        s.getId(),
                        s.getPlan().getId(),
                        s.getPlan().getCode(),
                        s.getStatus().name(),
                        s.getBillingPeriod(),
                        s.getCurrentPeriodStart(),
                        s.getCurrentPeriodEnd(),
                        s.getCancelAt(),
                        s.getTrialEndsAt())).toList();
    }

    /**
     * Starts a subscription checkout for the authenticated user. In dev mode (Stripe
     * disabled) the subscription is created directly and a local success URL is
     * returned; otherwise a provider checkout session is created and its URL/id are
     * returned. Moved out of {@code BillingController} so the controller stays thin.
     */
    @Transactional
    public SubscribeDtoOut subscribe(UUID userId, SubscribeDtoIn req) throws Exception {
        SubscriptionPlanEntity plan = getPlanByCode(req.getPlanCode());

        if (!stripeService.isEnabled()) {
            CustomerSubscriptionEntity sub = createSubscription(userId, req.getPlanCode(), req.getPeriod());
            return billingDtoMapper.toSubscribeDtoOut("/billing/success?dev=1", sub.getId().toString());
        }

        String priceId = "YEARLY".equalsIgnoreCase(req.getPeriod())
                ? plan.getStripeYearlyPriceId()
                : plan.getStripeMonthlyPriceId();
        var session = stripeService.createCheckoutSession(
                "user@example.com",
                priceId,
                "http://localhost:3003/billing/success",
                "http://localhost:3003/billing/cancel");
        return billingDtoMapper.toSubscribeDtoOut(session.getUrl(), session.getId());
    }
}
