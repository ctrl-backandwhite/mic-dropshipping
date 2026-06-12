package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;

import java.util.Map;

/**
 * Polymorphic gateway interface. Concrete implementations: {@code StripeGateway},
 * {@code PayPalGateway}, {@code UsdtGateway}.
 *
 * <p>{@link #initiate(PaymentEntity)} populates the payment with provider-specific fields
 * (clientSecret for Stripe, approveUrl for PayPal, cryptoAddress for USDT) and returns
 * the response payload returned to the frontend.
 *
 * <p>{@link #confirm(PaymentEntity, Map)} is invoked by webhook handlers to finalize.
 */
public interface PaymentGateway {

    boolean supports(PaymentMethod method);

    /** Provider name, for persistence (stripe, paypal, coinbase, manual). */
    String providerName();

    /** Initiate a payment. May enrich the entity with provider_ref + client metadata. */
    InitiateResult initiate(PaymentEntity payment);

    /** Confirm via webhook payload. Returns true if SUCCEEDED. */
    ConfirmResult confirm(PaymentEntity payment, Map<String, Object> providerPayload);

    record InitiateResult(String providerRef, String clientSecret, // Stripe only
            String approveUrl, // PayPal only
            String cryptoAddress, // USDT only
            String cryptoChain, String qrUrl, Map<String, Object> raw) {
    }

    record ConfirmResult(boolean succeeded, String errorMessage, Map<String, Object> raw) {
    }
}
