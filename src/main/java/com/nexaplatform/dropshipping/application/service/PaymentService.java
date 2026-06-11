package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.WalletDtos.RechargeResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.OrderPaymentIntentDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.OrderPaymentDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates wallet recharges across the 3 payment gateways. Idempotency-Key is honored:
 * the same key returns the same {@link PaymentEntity} without re-initiating with the
 * provider. Once a payment reaches SUCCEEDED via webhook, the wallet is credited (deposit).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final List<PaymentGateway> gateways;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final WalletService walletService;
    private final AuditLogger auditLogger;
    private final PartnerPlanSyncService partnerPlanSyncService;
    private final MeWalletDtoMapper meWalletDtoMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public PaymentEntity initiateRecharge(UUID userId, PaymentMethod method,
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

        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User"));
        WalletEntity wallet = walletService.getOrCreate(userId);

        PaymentEntity p = PaymentEntity.builder()
                .user(user).wallet(wallet)
                .method(method).status(PaymentStatus.PENDING)
                .amountDisplay(amountDisplay).currencyDisplay(currencyDisplay)
                .amountUsdCents(amountUsdCents)
                .settlementCurrency(method == PaymentMethod.USDT ? "USDT" : "USD")
                .idempotencyKey(idempotencyKey)
                .build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        var result = gw.initiate(p);

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        p.setProviderResponse(result.raw() != null ? result.raw() : new HashMap<>());
        if (result.cryptoAddress() != null) {
            p.setCryptoAddress(result.cryptoAddress());
            p.setCryptoChain(result.cryptoChain());
            p.setQrUrl(result.qrUrl());
            p.setCryptoExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        }
        p.setStatus(method == PaymentMethod.USDT ? PaymentStatus.REQUIRES_ACTION : PaymentStatus.REQUIRES_ACTION);
        p = paymentRepository.save(p);

        auditLogger.log("payment.initiate", user.getEmail(), Map.of(
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

    /**
     * Confirms the payment SUCCEEDED. Idempotent.
     * <ul>
     *  <li>Wallet-recharge payments → credit the wallet (deposit).</li>
     *  <li>Order payments → mark the order as PAID (no wallet credit; the order
     *      total has already been charged externally via Stripe/PayPal/USDT).</li>
     * </ul>
     */
    @Transactional
    public PaymentEntity confirmSucceeded(UUID paymentId, Map<String, Object> providerPayload) {
        PaymentEntity p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getStatus() == PaymentStatus.SUCCEEDED) return p;

        p.setStatus(PaymentStatus.SUCCEEDED);
        Map<String, Object> merged = new HashMap<>(p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.put("confirmed_at", Instant.now().toString());
        merged.putAll(providerPayload);
        p.setProviderResponse(merged);
        paymentRepository.save(p);

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
            auditLogger.log("order_payment.succeeded", p.getUser().getEmail(), Map.of(
                    "paymentId", p.getId(), "orderId", p.getOrderId(),
                    "method", p.getMethod(), "amount_usd_cents", p.getAmountUsdCents()));
        } else {
            // Recarga de wallet: acreditar saldo.
            String idempKey = "deposit-" + p.getId();
            walletService.deposit(p.getUser().getId(), p.getAmountUsdCents(),
                    p.getId(), idempKey,
                    "Wallet recharge via " + p.getMethod());
            auditLogger.log("payment.succeeded", p.getUser().getEmail(),
                    Map.of("paymentId", p.getId(), "method", p.getMethod(), "amount_usd_cents", p.getAmountUsdCents()));
        }
        return p;
    }

    @Transactional
    public PaymentEntity markFailed(UUID paymentId, String errorMessage, Map<String, Object> providerPayload) {
        PaymentEntity p = paymentRepository.findById(paymentId).orElseThrow();
        p.setStatus(PaymentStatus.FAILED);
        p.setErrorMessage(errorMessage);
        Map<String, Object> merged = new HashMap<>(p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.putAll(providerPayload != null ? providerPayload : Map.of());
        p.setProviderResponse(merged);
        return paymentRepository.save(p);
    }

    /** Used by PayPal-return endpoint after the buyer approves and the frontend reaches us. */
    @Transactional
    public PaymentEntity capturePayPal(UUID paymentId) {
        PaymentEntity p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
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

    public PaymentEntity find(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new NotFoundException("Payment"));
    }

    public List<PaymentEntity> listForUser(UUID userId) {
        return paymentRepository.findByUser_IdOrderByCreatedAtDesc(userId);
    }

    private PaymentGateway resolveGateway(PaymentMethod method) {
        return gateways.stream().filter(g -> g.supports(method)).findFirst()
                .orElseThrow(() -> new BusinessException("No gateway for method: " + method));
    }

    /* ============================================================
     *  Partner order payment (CARD / PAYPAL / USDT / WALLET)
     * ============================================================ */

    /**
     * Inicia un pago atado a un dropship order. El partner usa esto desde su backend
     * para cobrar el costo del fulfillment via Card/PayPal/USDT — sin pasar por la
     * recarga de wallet. Idempotency-Key honored.
     *
     * Devuelve el PaymentEntity con providerResponse poblado (clientSecret/approveUrl/cryptoAddress).
     */
    @Transactional
    public PaymentEntity initiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey) {
        if (method == null) throw new BusinessException("paymentMethod required");

        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }

        CustomerOrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order"));
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }
        // userId del partner_app (JWT subject derivado) puede no coincidir con el
        // owner de la orden — el partner_app es una "aplicación", no un usuario.
        // La autorización real ya viene del JWT con scope orders.write.

        long amountUsdCents = order.getTotalCents();
        if (amountUsdCents < 100) throw new BusinessException("Order total below $1.00 USD — refusing to charge");

        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null) throw new BusinessException("Cannot resolve payer user for this order");
        UserEntity user = userRepository.findById(payerUserId)
                .orElseThrow(() -> new NotFoundException("User"));
        WalletEntity wallet = walletService.getOrCreate(user.getId());

        PaymentEntity p = PaymentEntity.builder()
                .user(user).wallet(wallet)
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
        var result = gw.initiate(p);

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

        auditLogger.log("order_payment.initiate", user.getEmail(), Map.of(
                "orderId", orderId, "paymentId", p.getId(), "method", method, "amountCents", amountUsdCents));
        return p;
    }

    /**
     * Paga la orden con la wallet del partner (debito atomico). Si no hay saldo
     * suficiente, lanza BusinessException — el partner debe recargar antes.
     */
    @Transactional
    public PaymentEntity chargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey) {
        CustomerOrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order"));
        // Pagamos desde la wallet del owner de la orden (que es el partner user).
        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null) throw new BusinessException("Cannot resolve payer user for this order");

        long amountUsdCents = order.getTotalCents();
        UserEntity user = userRepository.findById(payerUserId).orElseThrow(() -> new NotFoundException("User"));
        WalletEntity wallet = walletService.getOrCreate(payerUserId);

        // El WalletService.charge ya valida saldo y maneja idempotencia.
        walletService.charge(payerUserId, amountUsdCents, orderId, idempotencyKey, "Order " + order.getOrderNumber());

        // Registramos el payment en SUCCEEDED para auditoría uniforme.
        PaymentEntity p = PaymentEntity.builder()
                .user(user).wallet(wallet)
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

        auditLogger.log("order_payment.wallet", user.getEmail(), Map.of(
                "orderId", orderId, "paymentId", p.getId(), "amountCents", amountUsdCents));
        return p;
    }

    /* ============================================================
     *  Me wallet use-cases (moved out of MeWalletController)
     * ============================================================ */

    /** Initiate a wallet recharge and project the provider metadata into the response DTO. */
    @Transactional
    public RechargeResponse rechargeWallet(UUID userId, PaymentMethod method, long amountUsdCents,
                                           String currencyDisplay, BigDecimal amountDisplay,
                                           String idempotencyKey, String cryptoChain) {
        PaymentEntity p = initiateRecharge(userId, method, amountUsdCents, currencyDisplay,
                amountDisplay, idempotencyKey, cryptoChain);
        Map<String, Object> meta = p.getProviderResponse() != null ? p.getProviderResponse() : Map.of();
        return new RechargeResponse(
                p.getId(), p.getMethod().name(), p.getStatus().name(),
                p.getAmountUsdCents(), p.getProvider(), p.getProviderRef(),
                (String) meta.get("clientSecret"),
                (String) meta.get("approveUrl"),
                (String) meta.get("cryptoAddress"),
                (String) meta.get("cryptoChain"),
                (String) meta.get("qrUrl"),
                p.getCryptoExpiresAt());
    }

    /**
     * Recharge the authenticated user's wallet from the transport DtoIn and return the
     * transport DtoOut. Parses the payment method and maps the result so the controller
     * stays free of business logic and manual mapping.
     */
    @Transactional
    public MeWalletRechargeDtoOut rechargeWalletDto(UUID userId, MeWalletRechargeDtoIn req, String idempotencyKey) {
        RechargeResponse response = rechargeWallet(
                userId,
                PaymentMethod.valueOf(req.getMethod()),
                req.getAmountUsdCents(),
                req.getCurrencyDisplay(),
                req.getAmountDisplay(),
                idempotencyKey,
                req.getCryptoChain());
        return meWalletDtoMapper.toRechargeDtoOut(response);
    }

    /** Capture a PayPal recharge and return its resulting status. */
    @Transactional
    public MeWalletPaymentStatusDtoOut capturePayPalResult(UUID paymentId) {
        PaymentEntity p = capturePayPal(paymentId);
        return MeWalletPaymentStatusDtoOut.builder()
                .paymentId(p.getId())
                .status(p.getStatus().name())
                .build();
    }

    /**
     * Dev-only: mock-confirm a wallet recharge so the UI can complete the flow when
     * providers are mocked. Returns the resulting status plus the credited balance.
     */
    @Transactional
    public MeWalletPaymentStatusDtoOut confirmMockRecharge(UUID userId, UUID paymentId) {
        PaymentEntity p = confirmSucceeded(paymentId, Map.of("mock_confirm", true));
        return MeWalletPaymentStatusDtoOut.builder()
                .paymentId(p.getId())
                .status(p.getStatus().name())
                .balanceUsdCents(walletService.getOrCreate(userId).getBalanceUsdCents())
                .build();
    }

    /* ============================================================
     *  Order-payment view use-cases (Partner + Me order payment controllers)
     * ============================================================ */

    /** Project a payment entity into the order-payment view, surfacing provider metadata. */
    public OrderPaymentDtoOut toPaymentView(PaymentEntity p) {
        Map<String, Object> meta = p.getProviderResponse() == null ? Map.of() : p.getProviderResponse();
        return OrderPaymentDtoOut.builder()
                .id(p.getId())
                .orderId(p.getOrderId())
                .method(p.getMethod().name())
                .status(p.getStatus().name())
                .amountUsdCents(p.getAmountUsdCents())
                .amountDisplay(p.getAmountDisplay())
                .currencyDisplay(p.getCurrencyDisplay())
                .provider(p.getProvider())
                .providerRef(p.getProviderRef())
                .clientSecret((String) meta.get("clientSecret"))
                .approveUrl((String) meta.get("approveUrl"))
                .cryptoAddress(p.getCryptoAddress())
                .cryptoChain(p.getCryptoChain())
                .qrUrl(p.getQrUrl())
                .cryptoExpiresAt(p.getCryptoExpiresAt())
                .createdAt(p.getCreatedAt())
                .build();
    }

    /**
     * Initiate an order payment for the requested method. WALLET charges the wallet
     * atomically; CARD/PAYPAL/USDT initiate the external provider flow. Returns the
     * resulting payment view.
     */
    @Transactional
    public OrderPaymentDtoOut initiateOrderPaymentView(UUID orderId, UUID userId, PaymentMethod method,
                                                       boolean wallet, String idempotencyKey) {
        PaymentEntity p = wallet
                ? chargeWalletForOrder(orderId, userId, idempotencyKey)
                : initiateOrderPayment(orderId, userId, method, idempotencyKey);
        return toPaymentView(p);
    }

    /**
     * Initiate an order payment for a partner identified by the OAuth2 {@link Jwt}.
     * Resolves the partner user id from the token and the payment method from the DtoIn,
     * keeping the partner controller free of business logic.
     */
    @Transactional
    public OrderPaymentDtoOut initiatePartnerOrderPayment(Jwt jwt, UUID orderId,
                                                          OrderPaymentIntentDtoIn req, String idempotencyKey) {
        UUID userId = resolvePartnerUserId(jwt);
        return initiateOrderPaymentView(
                orderId, userId, req.isWallet() ? null : req.toPaymentMethod(), req.isWallet(), idempotencyKey);
    }

    /**
     * Resolve the partner userId from the JWT. In the demo a deterministic UUID is derived
     * from the subject; in production this would look up partner_app by client_id and return
     * the owner. Moved here from the controller so the transport layer stays thin.
     */
    public UUID resolvePartnerUserId(Jwt jwt) {
        return UUID.nameUUIDFromBytes(("partner:" + jwt.getSubject()).getBytes());
    }

    /**
     * Initiate an order payment for the account owner (B2C). Resolves the payment method
     * from the DtoIn so the customer controller stays free of business logic.
     */
    @Transactional
    public OrderPaymentDtoOut initiateMeOrderPayment(UUID userId, UUID orderId,
                                                     OrderPaymentIntentDtoIn req, String idempotencyKey) {
        return initiateOrderPaymentView(
                orderId, userId, req.isWallet() ? null : req.toPaymentMethod(), req.isWallet(), idempotencyKey);
    }

    /** List all payment attempts for an order, newest first. */
    @Transactional(readOnly = true)
    public List<OrderPaymentDtoOut> listOrderPayments(UUID orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(this::toPaymentView).toList();
    }

    /** Read a single order payment, validating it belongs to the order. */
    @Transactional(readOnly = true)
    public OrderPaymentDtoOut getOrderPayment(UUID orderId, UUID paymentId) {
        PaymentEntity p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getOrderId() != null && !p.getOrderId().equals(orderId)) {
            throw new NotFoundException("Payment");
        }
        return toPaymentView(p);
    }

    /** Dev-only: mock-confirm a pending order payment without a real provider webhook. */
    @Transactional
    public OrderPaymentDtoOut confirmMockOrderPayment(UUID orderId, UUID paymentId) {
        PaymentEntity p = confirmSucceeded(paymentId,
                Map.of("mock_confirm", true, "orderId", orderId.toString()));
        return toPaymentView(p);
    }

    /* ============================================================
     *  Provider webhook handling (moved out of PaymentWebhookController)
     *  Signature verification stays in the controller (transport security);
     *  payload parsing + dispatch live here.
     * ============================================================ */

    /**
     * Handle a signature-verified Stripe event. Resolves the payment by metadata
     * or provider ref, then confirms/fails it; subscription events are forwarded
     * to the plan-sync service. Returns the HTTP body to echo back to Stripe.
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public String handleStripeEvent(String eventType, String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) root.get("data")).get("object");
            String intentId = String.valueOf(data.get("id"));
            Map<String, Object> meta = (Map<String, Object>) data.getOrDefault("metadata", Map.of());
            String paymentIdStr = meta != null ? String.valueOf(meta.get("paymentId")) : null;
            if (paymentIdStr == null || "null".equals(paymentIdStr)) {
                PaymentEntity p = paymentRepository.findByProviderAndProviderRef("stripe", intentId).orElse(null);
                if (p != null) paymentIdStr = p.getId().toString();
            }

            if ("customer.subscription.created".equals(eventType)
                    || "customer.subscription.updated".equals(eventType)
                    || "customer.subscription.deleted".equals(eventType)) {
                // Plan sync: when Stripe confirms/changes the subscription, refresh the
                // nexadrop.plan setting on the owner's OAuth clients. Already-issued tokens
                // stay valid until expiry (12h) and pick up the new plan on refresh.
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

    /** Handle a signature-verified PayPal event. Returns the HTTP body to echo back. */
    @SuppressWarnings("unchecked")
    @Transactional
    public String handlePayPalEvent(String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            String eventType = String.valueOf(root.get("event_type"));
            Map<String, Object> resource = (Map<String, Object>) root.get("resource");
            String orderId = String.valueOf(resource.get("id"));
            PaymentEntity p = paymentRepository.findByProviderAndProviderRef("paypal", orderId).orElse(null);
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

    /** Handle a signature-verified Coinbase event. Returns the HTTP body to echo back. */
    @SuppressWarnings("unchecked")
    @Transactional
    public String handleCoinbaseEvent(String payload) {
        try {
            Map<String, Object> root = objectMapper.readValue(payload, Map.class);
            Map<String, Object> event = (Map<String, Object>) root.get("event");
            String type = event != null ? String.valueOf(event.get("type")) : "";
            Map<String, Object> data = event != null ? (Map<String, Object>) event.get("data") : Map.of();
            String chargeCode = String.valueOf(data.get("code"));
            PaymentEntity p = paymentRepository.findByProviderAndProviderRef("coinbase", chargeCode).orElse(null);
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
