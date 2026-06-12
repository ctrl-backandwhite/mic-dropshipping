package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Billing use-case implementation. Operates on the {@link CustomerSubscription}
 * domain model and delegates aggregate persistence to its domain port. The legacy
 * Spring Data {@code SubscriptionPlanRepository} is reused as a read-only
 * collaborator for plan-by-code/Stripe-price-id resolution and the feature-derived
 * public plan list; plan administration mutations are delegated to the migrated
 * {@link SubscriptionPlanUseCase}. Preserves the Stripe-disabled dev path and the
 * exact checkout behaviour moved out of the old {@code SubscriptionService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSubscriptionUseCaseImpl implements CustomerSubscriptionUseCase {

    private final CustomerSubscriptionRepository customerSubscriptionRepository;
    private final CustomerSubscriptionUpdateMapper customerSubscriptionUpdateMapper;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionPlanUseCase subscriptionPlanUseCase;
    private final StripeService stripeService;

    @Override
    @Transactional
    public CustomerSubscription save(CustomerSubscription model) {
        CustomerSubscription saved = customerSubscriptionRepository.save(model);
        log.info("::> [BILLING] Subscription created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> findAll() {
        return customerSubscriptionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSubscription getById(UUID id) {
        CustomerSubscription model = customerSubscriptionRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Subscription not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    public CustomerSubscription update(CustomerSubscription model, UUID id) {
        CustomerSubscription existing = getById(id);
        customerSubscriptionUpdateMapper.updateFromModel(model, existing);
        CustomerSubscription saved = customerSubscriptionRepository.update(existing);
        log.info("::> [BILLING] Subscription updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        customerSubscriptionRepository.delete(id);
        log.info("::> [BILLING] Subscription deleted id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> listAdminSubscriptions(String status) {
        return customerSubscriptionRepository.findAll().stream()
                // DROP-634: normaliza estado y periodo en la propia consulta para que
                // el LISTADO de admin sea consistente aunque existan datos legacy que
                // escapen a la migración (FREE+TRIALING, periodo MONTH/YEAR).
                .map(this::normalizeForAdmin)
                .filter(s -> status == null || status.isBlank()
                        || (s.getStatus() != null && s.getStatus().name().equalsIgnoreCase(status)))
                .toList();
    }

    /**
     * DROP-634: defensa en profundidad sobre la vista de admin.
     * <ul>
     *   <li>Un plan gratuito (FREE) nunca debe figurar en {@code TRIALING}: un plan
     *       sin coste está activo desde el inicio, así que lo presentamos {@code ACTIVE}.</li>
     *   <li>El periodo se canonicaliza a {@code MONTHLY}/{@code YEARLY} (formato del
     *       use case real) para que el frontend siempre lo traduzca vía i18n y nunca
     *       muestre el valor crudo del enum.</li>
     * </ul>
     * No persiste: sólo ajusta el modelo de lectura que viaja al DTO.
     */
    private CustomerSubscription normalizeForAdmin(CustomerSubscription s) {
        CustomerSubscription out = s;
        boolean isFree = "FREE".equalsIgnoreCase(out.getPlanCode())
                || (out.getPriceMonthly() == 0 && out.getPriceYearly() == 0);
        if (isFree && out.getStatus() == SubscriptionStatus.TRIALING) {
            out = out.withStatus(SubscriptionStatus.ACTIVE).withTrialEndsAt(null);
        }
        out = out.withBillingPeriod(canonicalPeriod(out.getBillingPeriod()));
        return out;
    }

    /** Maps the historical period conventions to the canonical MONTHLY/YEARLY values. */
    private String canonicalPeriod(String period) {
        if (period == null) {
            return null;
        }
        return switch (period.trim().toUpperCase()) {
            case "MONTH", "MONTHLY" -> "MONTHLY";
            case "YEAR", "YEARLY" -> "YEARLY";
            default -> period.toUpperCase();
        };
    }

    @Override
    @Transactional
    public CustomerSubscription createSubscription(UUID userId, String planCode, String billingPeriod) {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);
        Instant now = Instant.now();
        Instant end = "YEARLY".equalsIgnoreCase(billingPeriod)
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);
        CustomerSubscription model = CustomerSubscription.builder().userId(userId).planId(plan.getId())
                .status(SubscriptionStatus.ACTIVE)
                .billingPeriod(billingPeriod == null ? "MONTHLY" : billingPeriod.toUpperCase()).currentPeriodStart(now)
                .currentPeriodEnd(end).build();
        return customerSubscriptionRepository.save(model);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> listForUser(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public SubscribeResult subscribe(UUID userId, String planCode, String period) throws Exception {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);

        if (!stripeService.isEnabled()) {
            CustomerSubscription sub = createSubscription(userId, planCode, period);
            return SubscribeResult.builder().checkoutUrl("/billing/success?dev=1").sessionId(sub.getId().toString())
                    .build();
        }

        String priceId = "YEARLY".equalsIgnoreCase(period)
                ? plan.getStripeYearlyPriceId()
                : plan.getStripeMonthlyPriceId();
        var session = stripeService.createCheckoutSession("user@example.com", priceId,
                "http://localhost:3003/billing/success", "http://localhost:3003/billing/cancel");
        return SubscribeResult.builder().checkoutUrl(session.getUrl()).sessionId(session.getId()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlan> listAdminPlans() {
        return subscriptionPlanUseCase.findAll().stream().sorted(Comparator.comparingInt(SubscriptionPlan::getPosition))
                .toList();
    }

    @Override
    @Transactional
    public SubscriptionPlan updatePlan(String code, SubscriptionPlan changes) {
        SubscriptionPlanEntity plan = getPlanEntityByCode(code);
        // Overlay only the editable fields onto the current plan so the delegated
        // update (which copies every non-audit field) cannot blank out the
        // non-editable columns (code/currency/position), preserving the legacy
        // updatePlan contract.
        SubscriptionPlan current = subscriptionPlanUseCase.getById(plan.getId());
        SubscriptionPlan merged = current.withName(changes.getName() != null ? changes.getName() : current.getName())
                .withDescription(changes.getDescription() != null ? changes.getDescription() : current.getDescription())
                .withPriceMonthlyCents(changes.getPriceMonthlyCents() != 0
                        ? changes.getPriceMonthlyCents()
                        : current.getPriceMonthlyCents())
                .withPriceYearlyCents(changes.getPriceYearlyCents() != 0
                        ? changes.getPriceYearlyCents()
                        : current.getPriceYearlyCents())
                .withActive(changes.isActive());
        SubscriptionPlan updated = subscriptionPlanUseCase.update(merged, plan.getId());
        log.info("::> [BILLING] Plan updated code={}", code);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlanEntity> listPublicPlans() {
        return planRepository.findByActiveTrueOrderByPositionAsc();
    }

    /** Resolves the managed plan entity by its unique code, failing if it does not exist. */
    private SubscriptionPlanEntity getPlanEntityByCode(String code) {
        return planRepository.findByCode(code).orElseThrow(() -> new NotFoundException("Plan not found: " + code));
    }
}
