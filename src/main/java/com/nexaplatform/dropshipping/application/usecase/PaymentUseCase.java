package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.model.Payment;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Use-case port for payments across the wallet-recharge and order-payment flows.
 * Operates on the {@link Payment} domain model. Idempotency-Key is honored: the
 * same key returns the same payment without re-initiating with the provider.
 */
public interface PaymentUseCase extends BaseUseCase<Payment, Payment, UUID> {

    /* ============ Wallet recharge ============ */

    /**
     * Initiate a wallet recharge with the resolved gateway; returns the payment with provider metadata.
     * The canonical USD amount is derived in the backend from {@code amountDisplay}+{@code currencyDisplay}
     * (single source of truth); {@code amountUsdCents} is only an optional fallback when no display amount
     * is provided.
     */
    Payment initiateRecharge(UUID userId, PaymentMethod method, Long amountUsdCents, String currencyDisplay,
            BigDecimal amountDisplay, String idempotencyKey, String cryptoChain);

    /** Rounded recharge presets in the active currency, already formatted by the backend. */
    RechargeOptions rechargeOptions(String currency);

    /** Confirm a payment SUCCEEDED (idempotent): credit the wallet, or mark the order PAID. */
    Payment confirmSucceeded(UUID paymentId, Map<String, Object> providerPayload);

    /** Mark a payment FAILED with the given error message. */
    Payment markFailed(UUID paymentId, String errorMessage, Map<String, Object> providerPayload);

    /** Capture an approved PayPal recharge and confirm/fail accordingly. */
    Payment capturePayPal(UUID userId, UUID paymentId);

    /** Dev-only mock-confirm of a wallet recharge; returns the (re)credited wallet balance in cents. */
    Payment confirmMockRecharge(UUID userId, UUID paymentId);

    /**
     * Confirm a wallet recharge on return from the provider (Stripe Checkout Session / PayPal): verifies
     * the charge settled and credits the wallet (idempotent). Mirrors {@code confirmOrderPayment}.
     */
    Payment confirmRecharge(UUID userId, UUID paymentId);

    /** A single payment by id. */
    Payment find(UUID id);

    /** Payments initiated by a user, newest first. */
    List<Payment> listForUser(UUID userId);

    /* ============ Order payment ============ */

    /** Initiate an order payment via an external provider (CARD/PAYPAL/USDT). */
    Payment initiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey);

    /** Pay the order from the wallet atomically; records a SUCCEEDED payment. */
    Payment chargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey);

    /** Initiate an order payment (WALLET charges atomically; else external provider flow). */
    Payment initiateOrderPaymentView(UUID orderId, UUID userId, PaymentMethod method, boolean wallet,
            String idempotencyKey);

    /** Initiate an order payment for a partner identified by the OAuth2 JWT. */
    Payment initiatePartnerOrderPayment(Jwt jwt, UUID orderId, boolean wallet, PaymentMethod method,
            String idempotencyKey);

    /** Initiate an order payment for the account owner (B2C). */
    Payment initiateMeOrderPayment(UUID userId, UUID orderId, boolean wallet, PaymentMethod method,
            String idempotencyKey);

    /** All payment attempts for an order, newest first. */
    List<Payment> listOrderPayments(UUID orderId);

    /** Como {@link #listOrderPayments} pero validando que el pedido es del PARTNER del JWT (IDOR entre partners). */
    List<Payment> listOrderPaymentsForPartner(org.springframework.security.oauth2.jwt.Jwt jwt, UUID orderId);

    /** A single order payment, validating it belongs to the order. */
    Payment getOrderPayment(UUID orderId, UUID paymentId);

    /** Como {@link #getOrderPayment} pero validando que el pedido es del PARTNER del JWT (IDOR entre partners). */
    Payment getOrderPaymentForPartner(org.springframework.security.oauth2.jwt.Jwt jwt, UUID orderId, UUID paymentId);

    /** Dev-only mock-confirm of a pending order payment. */
    Payment confirmMockOrderPayment(UUID userId, UUID orderId, UUID paymentId);

    /**
     * Confirm an order payment against the REAL provider on buyer return: Stripe Checkout
     * Session retrieve (payment_status=paid) or PayPal Orders capture. Falls back to a mock
     * confirm when the provider is disabled. Marks the order PAID on success.
     */
    Payment confirmOrderPayment(UUID userId, UUID orderId, UUID paymentId);

    /**
     * Refund a SUCCEEDED order payment at the provider (Stripe Refund / PayPal capture refund).
     * {@code amountCents <= 0} performs a full refund. Marks the payment REFUNDED.
     */
    Payment refundOrderPayment(UUID orderId, UUID paymentId, long amountCents);

    /* ============ Webhook dispatch (signature verified in the controller) ============ */

    /** Handle a signature-verified Stripe event; returns the HTTP body to echo back. */
    String handleStripeEvent(String eventType, String payload);

    /** Handle a signature-verified PayPal event; returns the HTTP body to echo back. */
    String handlePayPalEvent(String payload);

    /** Handle a signature-verified Coinbase event; returns the HTTP body to echo back. */
    String handleCoinbaseEvent(String payload);
}
