package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;

import java.util.UUID;

/**
 * Use-case port for subscription plans. Operates purely on the
 * {@link SubscriptionPlan} domain model.
 */
public interface SubscriptionPlanUseCase extends BaseUseCase<SubscriptionPlan, SubscriptionPlan, UUID> {
}
