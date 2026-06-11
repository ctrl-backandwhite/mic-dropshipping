package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.WebhookDelivery;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for webhook subscriptions; operates on the
 * {@link WebhookSubscription} domain model. Beyond the standard CRUD it exposes
 * the command operations specific to this aggregate: secret rotation, firing a
 * test delivery, and listing recent deliveries (the nested {@link WebhookDelivery}).
 */
public interface WebhookSubscriptionUseCase extends BaseUseCase<WebhookSubscription, WebhookSubscription, UUID> {

    /** Rotate the signing secret and return the updated subscription model. */
    WebhookSubscription rotate(UUID id);

    /** Fire a synthetic test delivery and return the targeted subscription model. */
    WebhookSubscription fireTest(UUID id);

    /** List the most recent delivery attempts for a subscription. */
    List<WebhookDelivery> deliveries(UUID id);
}
