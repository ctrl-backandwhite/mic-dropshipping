package com.nexaplatform.dropshipping.api.controller;

/**
 * @deprecated Replaced by {@link PaymentWebhookController}. Kept as an empty class to avoid
 * breaking external references; the actual Stripe webhook is at the same URL handled by
 * {@code PaymentWebhookController.stripe}.
 */
@Deprecated
public class StripeWebhookController {
    private StripeWebhookController() {
        /* intentionally empty */ }
}
