package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a customer subscription (the Billing aggregate root).
 * Use cases operate on this model; mappers translate to/from DtoIn/DtoOut (api)
 * and the JPA entity (infra).
 *
 * <p>The managed {@code user} and {@code plan} relations are carried in flattened
 * form: {@code userId}/{@code planId} are the foreign keys the repository adapter
 * resolves back into managed entities on save, while {@code planCode},
 * {@code userEmail}, {@code priceMonthly} and {@code priceYearly} are computed
 * read-only fields filled from those relations (used by the admin view); they
 * have no column on the subscription table and are ignored on {@code toEntity}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSubscription {

    private UUID id;
    private UUID userId;
    private UUID planId;
    private SubscriptionStatus status;
    private String billingPeriod;
    private String stripeCustomerId;
    private String stripeSubscriptionId;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant cancelAt;
    private Instant canceledAt;
    private Instant trialEndsAt;
    private String pendingPlanCode;
    private Instant pendingPlanAt;
    private Instant cancelReminderLastAt;

    /** Computed read field: the plan code of the related plan (admin/user views). */
    private String planCode;
    /** Computed read field: the email of the owning user (admin view). */
    private String userEmail;
    /** Computed read field: the related plan monthly price in cents (admin view). */
    private int priceMonthly;
    /** Computed read field: the related plan yearly price in cents (admin view). */
    private int priceYearly;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
