package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Billing aggregate root {@link CustomerSubscription}.
 * Absorbs the subscription logic that used to live in {@code SubscriptionService}
 * (admin listing, creation, per-user listing, Stripe checkout) and operates on the
 * domain model. The plan-side helpers stay here because they share the Billing
 * cluster's collaborators; mutations are delegated to {@link SubscriptionPlanUseCase}.
 */
public interface CustomerSubscriptionUseCase extends BaseUseCase<CustomerSubscription, CustomerSubscription, UUID> {

    /** Admin listing of all subscriptions, optionally filtered by status name. */
    List<CustomerSubscription> listAdminSubscriptions(String status);

    /** Creates an ACTIVE subscription for the user/plan/period and returns the saved model. */
    CustomerSubscription createSubscription(UUID userId, String planCode, String billingPeriod);

    /** All subscriptions owned by the given user (storefront listing). */
    List<CustomerSubscription> listForUser(UUID userId);

    /**
     * Starts a subscription checkout for the user. In dev mode (Stripe disabled)
     * the subscription is created directly and a local success URL is returned;
     * otherwise a provider checkout session is created and its URL/id are returned.
     */
    SubscribeResult subscribe(UUID userId, String planCode, String period) throws Exception;

    /** Admin plan listing, ordered by position, as domain models. */
    List<SubscriptionPlan> listAdminPlans();

    /** Applies a partial update to the plan identified by {@code code} (delegated to the plan use case). */
    SubscriptionPlan updatePlan(String code, SubscriptionPlan changes);

    /** Active public plan entities ordered by position (kept as entities for the feature-derived limits). */
    List<SubscriptionPlanEntity> listPublicPlans();
}
