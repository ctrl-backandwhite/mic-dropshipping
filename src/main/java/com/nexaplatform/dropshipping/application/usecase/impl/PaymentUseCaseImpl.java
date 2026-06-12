package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates wallet recharges and order payments across the 3 payment gateways.
 * Idempotency-Key is honored: the same key returns the same {@link Payment} without
 * re-initiating with the provider. Once a payment reaches SUCCEEDED, the wallet is
 * credited (deposit) or the order is marked PAID.
 *
 * <p>Operates on the {@link Payment} domain model and delegates persistence to the
 * domain port. The gateway collaborators require the managed {@code PaymentEntity}
 * (they read the generated id + the managed user), so the JPA adapter is used to
 * fetch the persisted entity for the gateway call only. Logic moved verbatim out of
 * the legacy {@code PaymentService}, preserving the money semantics exactly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentUseCaseImpl implements PaymentUseCase {

    private final List<PaymentGateway> gateways;
    private final PaymentRepository paymentRepository;
    private final PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final WalletUseCase walletUseCase;
    private final AuditLogger auditLogger;
    private final PartnerPlanSyncService partnerPlanSyncService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Payment initiateRecharge(UUID userId, PaymentMethod method,
                                    long amountUsdCents, String currencyDisplay,
                                    BigDecimal amountDisplay, String idempotencyKey,
                                    String cryptoChain) {
        if (amountUsdCents < 100) throw new BusinessException("Minimum recharge is $1.00 USD");
        if (amountUsdCents > 1_000_000_00L) throw new BusinessException("Maximum recharge is $1,000,000 USD");

        // idempotency
        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Returning existing payment for idempotency-key={}", idempotencyKey);
                return existing.get();
            }
        }

        if (userRepository.findById(userId).isEmpty()) throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(userId);

        Payment p = Payment.builder()
                .userId(userId).walletId(wallet.getId())
                .method(method).status(PaymentStatus.PENDING)
                .amountDisplay(amountDisplay).currencyDisplay(currencyDisplay)
                .amountUsdCents(amountUsdCents)
                .settlementCurrency(method == PaymentMethod.USDT ? "USDT" : "USD")
                .idempotencyKey(idempotencyKey)
                .build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        var result = gw.initiate(managedEntity(p.getId()));

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        p.setProviderResponse(result.raw() != null ? result.raw() : new HashMap<>());
        if (result.cryptoAddress() != null) {
            p.setCryptoAddress(result.cryptoAddress());
            p.setCryptoChain(result.cryptoChain());
            p.setQrUrl(result.qrUrl());
            p.setCryptoExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        }
        p.setStatus(PaymentStatus.REQUIRES_ACTION);
        p = paymentRepository.save(p);

        auditLogger.log("payment.initiate", p.getUserEmail(), Map.of(
                "paymentId", p.getId(), "method", method, "amount_usd_cents", amountUsdCents));

        // attach client metadata to provider_response so the controller can return it
        Map<String, Object> meta = new HashMap<>(p.getProviderResponse());
        if (result.clientSecret() != null) meta.put("clientSecret", result.clientSecret());
        if (result.approveUrl() != null) meta.put("approveUrl", result.approveUrl());
        if (result.cryptoAddress() != null) {
            meta.put("cryptoAddress", result.cryptoAddress());
            meta.put("cryptoChain", result.cryptoChain());
            meta.put("qrUrl", result.qrUrl());
            meta.put("expiresAt", p.getCryptoExpiresAt() != null ? p.getCryptoExpiresAt().toString() : null);
        }
        p.setProviderResponse(meta);
        return paymentRepository.save(p);
    }

    @Override
    @Transactional
    public Payment confirmSucceeded(UUID paymentId, Map<String, Object> providerPayload) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getStatus() == PaymentStatus.SUCCEEDED) return p;

        p.setStatus(PaymentStatus.SUCCEEDED);
        Map<String, Object> merged = new HashMap<>(p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.put("confirmed_at", Instant.now().toString());
        merged.putAll(providerPayload);
        p.setProviderResponse(merged);
        p = paymentRepository.save(p);

        boolean isOrderPayment = "ORDER_PAYMENT".equals(p.getPurpose()) && p.getOrderId() != null;
        if (isOrderPayment) {
            // El cobro externo (Stripe/PayPal/USDT) ya capturó el dinero. Marcamos
            // la orden como PAID para que el FulfillmentService la recoja.
            orderRepository.findById(p.getOrderId()).ifPresent(o -> {
                if (o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.AWAITING_PAYMENT) {
                    o.setStatus(OrderStatus.PAID);
                    orderRepository.save(o);
                }
            });
            auditLogger.log("order_payment.succeeded", p.getUserEmail(), Map.of(
                    "paymentId", p.getId(), "orderId", p.getOrderId(),
                    "method", p.getMethod(), "amount_usd_cents", p.getAmountUsdCents()));
        } else {
            // Recarga de wallet: acreditar saldo.
            String idempKey = "deposit-" + p.getId();
            walletUseCase.deposit(p.getUserId(), p.getAmountUsdCents(),
                    p.getId(), idempKey,
                    "Wallet recharge via " + p.getMethod());
            auditLogger.log("payment.succeeded", p.getUserEmail(),
                    Map.of("paymentId", p.getId(), "method", p.getMethod(), "amount_usd_cents", p.getAmountUsdCents()));
        }
        return p;
    }

    @Override
    @Transactional
    public Payment markFailed(UUID paymentId, String errorMessage, Map<String, Object> providerPayload) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow();
        p.setStatus(PaymentStatus.FAILED);
        p.setErrorMessage(errorMessage);
        Map<String, Object> merged = new HashMap<>(p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.putAll(providerPayload != null ? providerPayload : Map.of());
        p.setProviderResponse(merged);
        return paymentRepository.save(p);
    }

    @Override
    @Transactional
    public Payment capturePayPal(UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getMethod() != PaymentMethod.PAYPAL) throw new BusinessException("Not a PayPal payment");
        PaymentGateway gw = resolveGateway(PaymentMethod.PAYPAL);
        if (!(gw instanceof com.nexaplatform.dropshipping.infrastructure.integration.payment.PayPalGateway pp)) {
            throw new BusinessException("PayPal gateway not configured");
        }
        Map<String, Object> resp = pp.capture(p.getProviderRef());
        String status = String.valueOf(resp.getOrDefault("status", ""));
        if ("COMPLETED".equalsIgnoreCase(status) || Boolean.TRUE.equals(resp.get("mock"))) {
            return confirmSucceeded(p.getId(), resp);
        }
        return markFailed(p.getId(), "PayPal capture returned " + status, resp);
    }

    @Override
    @Transactional
    public Payment confirmMockRecharge(UUID userId, UUID paymentId) {
        return confirmSucceeded(paymentId, Map.of("mock_confirm", true));
    }

    @Override
    @Transactional(readOnly = true)
    public Payment find(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new NotFoundException("Payment"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> listForUser(UUID userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    private PaymentGateway resolveGateway(PaymentMethod method) {
        return gateways.stream().filter(g -> g.supports(method)).findFirst()
                .orElseThrow(() -> new BusinessException("No gateway for method: " + method));
    }

    /** Fetches the managed entity for the gateway call (gateways read the persisted id + user). */
    private PaymentEntity managedEntity(UUID paymentId) {
        return paymentJpaRepositoryAdapter.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment"));
    }

    /* ============================================================
     *  Partner / customer order payment (CARD / PAYPAL / USDT / WALLET)
     * ============================================================ */

    @Override
    @Transactional
    public Payment initiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey) {
        if (method == null) throw new BusinessException("paymentMethod required");

        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }

        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }

        long amountUsdCents = order.getTotalCents();
        if (amountUsdCents < 100) throw new BusinessException("Order total below $1.00 USD — refusing to charge");

        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null) throw new BusinessException("Cannot resolve payer user for this order");
        if (userRepository.findById(payerUserId).isEmpty()) throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);

        Payment p = Payment.builder()
                .userId(payerUserId).walletId(wallet.getId())
                .method(method).status(PaymentStatus.PENDING)
                .amountUsdCents(amountUsdCents)
                .amountDisplay(BigDecimal.valueOf(amountUsdCents).movePointLeft(2))
                .currencyDisplay(order.getCurrency() != null ? order.getCurrency() : "USD")
                .settlementCurrency(method == PaymentMethod.USDT ? "USDT" : "USD")
                .idempotencyKey(idempotencyKey)
                .orderId(orderId)
                .purpose("ORDER_PAYMENT")
                .build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        var result = gw.initiate(managedEntity(p.getId()));

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        Map<String, Object> meta = result.raw() != null ? new HashMap<>(result.raw()) : new HashMap<>();
        if (result.clientSecret() != null)   meta.put("clientSecret", result.clientSecret());
        if (result.approveUrl() != null)     meta.put("approveUrl", result.approveUrl());
        if (result.cryptoAddress() != null) {
            meta.put("cryptoAddress", result.cryptoAddress());
            meta.put("cryptoChain", result.cryptoChain());
            meta.put("qrUrl", result.qrUrl());
            p.setCryptoAddress(result.cryptoAddress());
            p.setCryptoChain(result.cryptoChain());
            p.setQrUrl(result.qrUrl());
            p.setCryptoExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
            meta.put("expiresAt", p.getCryptoExpiresAt().toString());
        }
        p.setProviderResponse(meta);
        p.setStatus(PaymentStatus.REQUIRES_ACTION);
        p = paymentRepository.save(p);

        auditLogger.log("order_payment.initiate", p.getUserEmail(), Map.of(
                "orderId", orderId, "paymentId", p.getId(), "method", method, "amountCents", amountUsdCents));
        return p;
    }

    @Override
    @Transactional
    public Payment chargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null) throw new BusinessException("Cannot resolve payer user for this order");

        long amountUsdCents = order.getTotalCents();
        if (userRepository.findById(payerUserId).isEmpty()) throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);

        // El WalletUseCase.charge ya valida saldo y maneja idempotencia.
        walletUseCase.charge(payerUserId, amountUsdCents, orderId, idempotencyKey, "Order " + order.getOrderNumber());

        // Registramos el payment en SUCCEEDED para auditoría uniforme.
        Payment p = Payment.builder()
                .userId(payerUserId).walletId(wallet.getId())
                .method(PaymentMethod.CARD) // sentinel: wallet no es un PaymentMethod del enum
                .status(PaymentStatus.SUCCEEDED)
                .amountUsdCents(amountUsdCents)
                .amountDisplay(BigDecimal.valueOf(amountUsdCents).movePointLeft(2))
                .currencyDisplay("USD")
                .settlementCurrency("USD")
                .provider("wallet")
                .idempotencyKey(idempotencyKey)
                .orderId(orderId)
                .purpose("ORDER_PAYMENT")
                .providerResponse(Map.of("walletId", wallet.getId().toString(), "settled", "atomic"))
                .build();
        p = paymentRepository.save(p);

        // La orden pasa a PAID — el FulfillmentService la recogerá.
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        auditLogger.log("order_payment.wallet", p.getUserEmail(), Map.of(
                "orderId", orderId, "paymentId", p.getId(), "amountCents", amountUsdCents));
        return p;
    }

    @Override
    @Transactional
    public Payment initiateOrderPaymentView(UUID orderId, UUID userId, PaymentMethod method,
                                            boolean wallet, String idempotencyKey) {
        return wallet
                ? chargeWalletForOrder(orderId, userId, idempotencyKey)
                : initiateOrderPayment(orderId, userId, method, idempotencyKey);
    }

    @Override
    @Transactional
    public Payment initiatePartnerOrderPayment(Jwt jwt, UUID orderId, boolean wallet,
                                               PaymentMethod method, String idempotencyKey) {
        UUID userId = resolvePartnerUserId(jwt);
        return initiateOrderPaymentView(orderId, userId, method, wallet, idempotencyKey);
    }

    /**
     * Resolve the partner userId from the JWT. In the demo a deterministic UUID is derived
     * from the subject; in production this would look up partner_app by client_id and return
     * the owner.
     */
    private UUID resolvePartnerUserId(Jwt jwt) {
        return UUID.nameUUIDFromBytes(("partner:" + jwt.getSubject()).getBytes());
    }

    @Override
    @Transactional
    public Payment initiateMeOrderPayment(UUID userId, UUID orderId, boolean wallet,
                                          PaymentMethod method, String idempotencyKey) {
        return initiateOrderPaymentView(orderId, userId, method, wallet, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> listOrderPayments(UUID orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getOrderPayment(UUID orderId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getOrderId() != null && !p.getOrderId().equals(orderId)) {
            throw new NotFoundException("Payment");
        }
        return p;
    }

    @Override
    @Transactional
    public Payment confirmMockOrderPayment(UUID orderId, UUID paymentId) {
        return confirmSucceeded(paymentId, Map.of("mock_confirm", true, "orderId", orderId.toString()));
    }

    /* ============================================================
     *  Provider webhook handling (signature verified in the controller)
     * ============================================================ */

    @SuppressWarnings("unchecked")
    @Override
    @Transactional
    public String handleStripeEvent(String eventType, String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) root.get("data")).get("object");
            String intentId = String.valueOf(data.get("id"));
            Map<String, Object> meta = (Map<String, Object>) data.getOrDefault("metadata", Map.of());
            String paymentIdStr = meta != null ? String.valueOf(meta.get("paymentId")) : null;
            if (paymentIdStr == null || "null".equals(paymentIdStr)) {
                Payment p = paymentRepository.findByProviderAndProviderRef("stripe", intentId).orElse(null);
                if (p != null) paymentIdStr = p.getId().toString();
            }

            if ("customer.subscription.created".equals(eventType)
                    || "customer.subscription.updated".equals(eventType)
                    || "customer.subscription.deleted".equals(eventType)) {
                String stripeSubId = String.valueOf(data.get("id"));
                String stripeStatus = String.valueOf(data.get("status"));
                partnerPlanSyncService.onSubscriptionEvent(stripeSubId, stripeStatus, eventType);
                return "ok";
            }

            if (paymentIdStr == null) return "no-match";
            UUID paymentId = UUID.fromString(paymentIdStr);
            if ("payment_intent.succeeded".equals(eventType)) {
                confirmSucceeded(paymentId, data);
            } else if ("payment_intent.payment_failed".equals(eventType)) {
                markFailed(paymentId, "Stripe: payment_failed", data);
            }
        } catch (Exception e) {
            log.error("Stripe webhook processing failed: {}", e.getMessage(), e);
        }
        return "ok";
    }

    @SuppressWarnings("unchecked")
    @Override
    @Transactional
    public String handlePayPalEvent(String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            String eventType = String.valueOf(root.get("event_type"));
            Map<String, Object> resource = (Map<String, Object>) root.get("resource");
            String orderId = String.valueOf(resource.get("id"));
            Payment p = paymentRepository.findByProviderAndProviderRef("paypal", orderId).orElse(null);
            if (p == null) return "no-match";
            if (eventType != null && eventType.contains("CAPTURE.COMPLETED")) {
                confirmSucceeded(p.getId(), resource);
            } else if (eventType != null && eventType.contains("DENIED")) {
                markFailed(p.getId(), "PayPal: " + eventType, resource);
            }
        } catch (Exception e) {
            log.error("PayPal webhook processing failed: {}", e.getMessage(), e);
        }
        return "ok";
    }

    @SuppressWarnings("unchecked")
    @Override
    @Transactional
    public String handleCoinbaseEvent(String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            Map<String, Object> event = (Map<String, Object>) root.get("event");
            String type = event != null ? String.valueOf(event.get("type")) : "";
            Map<String, Object> data = event != null ? (Map<String, Object>) event.get("data") : Map.of();
            String chargeCode = String.valueOf(data.get("code"));
            Payment p = paymentRepository.findByProviderAndProviderRef("coinbase", chargeCode).orElse(null);
            if (p == null) return "no-match";
            if ("charge:confirmed".equals(type)) {
                confirmSucceeded(p.getId(), data);
            } else if ("charge:failed".equals(type) || "charge:delayed".equals(type)) {
                markFailed(p.getId(), "Coinbase: " + type, data);
            }
        } catch (Exception e) {
            log.error("Coinbase webhook processing failed: {}", e.getMessage(), e);
        }
        return "ok";
    }
}
