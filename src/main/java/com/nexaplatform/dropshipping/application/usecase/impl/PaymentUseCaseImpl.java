package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.RechargeOptions;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PayPalGateway;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.StripeGateway;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final CustomerSubscriptionUseCase customerSubscriptionUseCase;
    private final SubscriptionNotificationService subscriptionNotificationService;
    private final ObjectMapper objectMapper;
    private final OrderEmailService orderEmailService;
    private final CurrencyRateService currencyRateService;
    private final StockService stockService;

    @Override
    @Transactional
    public Payment initiateRecharge(UUID userId, PaymentMethod method, Long amountUsdCents, String currencyDisplay,
            BigDecimal amountDisplay, String idempotencyKey, String cryptoChain) {
        // El importe canónico en USD se calcula EN EL BACKUP a partir de lo que el usuario introdujo en su
        // divisa activa (amountDisplay + currencyDisplay). Solo se usa amountUsdCents del cliente como
        // fallback si no llega importe en divisa (compatibilidad hacia atrás).
        long usdCents = resolveRechargeUsdCents(amountUsdCents, currencyDisplay, amountDisplay);
        if (usdCents < 100)
            throw new BusinessException("Minimum recharge is $1.00 USD");
        if (usdCents > 1_000_000_00L)
            throw new BusinessException("Maximum recharge is $1,000,000 USD");

        // idempotency
        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Returning existing payment for idempotency-key={}", idempotencyKey);
                return existing.get();
            }
        }

        if (userRepository.findById(userId).isEmpty())
            throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(userId);

        // Moneda de cobro de la recarga: MISMA lógica que el checkout. Con Stripe (CARD) se cobra en EUR si
        // el usuario trabaja la web en EUR; en cualquier otra divisa se cobra el equivalente en USD. PayPal
        // liquida en USD y USDT en USDT. El saldo del wallet SIEMPRE se acredita en USD canónico.
        String displayCcy = currencyDisplay != null && !currencyDisplay.isBlank() ? currencyDisplay : "USD";
        boolean stripeEur = method == PaymentMethod.CARD && "EUR".equalsIgnoreCase(displayCcy);
        String settlementCcy = method == PaymentMethod.USDT ? "USDT" : (stripeEur ? "EUR" : "USD");
        BigDecimal settlementAmount = rechargeSettlementAmount(settlementCcy, displayCcy, usdCents, amountDisplay);

        Payment p = Payment.builder().userId(userId).walletId(wallet.getId()).method(method)
                .status(PaymentStatus.PENDING).amountDisplay(amountDisplay).currencyDisplay(currencyDisplay)
                .amountUsdCents(usdCents).settlementCurrency(settlementCcy).settlementAmount(settlementAmount)
                .idempotencyKey(idempotencyKey).build();
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

        auditLogger.log("payment.initiate", p.getUserEmail(),
                Map.of("paymentId", p.getId(), "method", method, "amount_usd_cents", usdCents));

        // attach client metadata to provider_response so the controller can return it
        Map<String, Object> meta = new HashMap<>(p.getProviderResponse());
        if (result.clientSecret() != null)
            meta.put("clientSecret", result.clientSecret());
        if (result.approveUrl() != null)
            meta.put("approveUrl", result.approveUrl());
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
     * Importe a cobrar de la recarga en la moneda de liquidación:
     * <ul>
     *   <li>USD → el USD canónico (amountUsdCents / 100).</li>
     *   <li>Liquidación == divisa que ve el usuario (EUR) y viene su importe → se cobra EXACTAMENTE lo que
     *       introdujo, sin reconvertir (evita desfases de céntimos).</li>
     *   <li>En otro caso → se convierte el USD canónico a la moneda de liquidación con la tasa del día.</li>
     * </ul>
     */
    private BigDecimal rechargeSettlementAmount(String settlementCcy, String displayCcy, long amountUsdCents,
            BigDecimal amountDisplay) {
        BigDecimal usd = BigDecimal.valueOf(amountUsdCents).movePointLeft(2);
        if ("USD".equalsIgnoreCase(settlementCcy)) {
            return usd;
        }
        if (amountDisplay != null && settlementCcy.equalsIgnoreCase(displayCcy)) {
            return amountDisplay;
        }
        return currencyRateService.usdTo(usd, settlementCcy);
    }

    /** Importes base de recarga (en USD) sobre los que se generan los presets de cada divisa. */
    private static final int[] RECHARGE_PRESETS_USD = { 10, 25, 50, 100, 250, 500 };

    /**
     * Importe canónico en USD (céntimos) de la recarga. Se calcula EN EL BACKEND a partir de lo que el
     * usuario introdujo en su divisa activa; solo se cae al {@code amountUsdCents} del cliente si no llega
     * importe en divisa (compatibilidad).
     */
    private long resolveRechargeUsdCents(Long amountUsdCents, String currencyDisplay, BigDecimal amountDisplay) {
        if (amountDisplay != null && amountDisplay.signum() > 0) {
            String ccy = currencyDisplay != null && !currencyDisplay.isBlank() ? currencyDisplay : "USD";
            return currencyRateService.toUsd(amountDisplay, ccy).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        if (amountUsdCents != null && amountUsdCents > 0) {
            return amountUsdCents;
        }
        throw new BusinessException("Recharge amount is required");
    }

    @Override
    public RechargeOptions rechargeOptions(String currency) {
        String ccy = currency != null && !currency.isBlank() ? currency.toUpperCase(Locale.ROOT) : "USD";
        // EUR/USD conservan los importes estándar (10/25/50/…); el resto se convierten y se REDONDEAN a un
        // número "bonito" (2 cifras significativas) para no mostrar cantidades como 41 234 o 353 217.
        boolean standard = "USD".equals(ccy) || "EUR".equals(ccy);
        List<RechargeOptions.Preset> presets = new ArrayList<>(RECHARGE_PRESETS_USD.length);
        for (int base : RECHARGE_PRESETS_USD) {
            BigDecimal amount = standard ? BigDecimal.valueOf(base)
                    : niceRound(currencyRateService.usdTo(BigDecimal.valueOf(base), ccy));
            presets.add(new RechargeOptions.Preset(amount, currencyRateService.formatDisplay(amount, ccy)));
        }
        return new RechargeOptions(ccy, currencyRateService.symbolOf(ccy), presets);
    }

    /** Redondea a 2 cifras significativas (41 234 → 41 000; 1 490 → 1 500; 306 → 310). */
    private static BigDecimal niceRound(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        double d = value.doubleValue();
        int magnitude = (int) Math.floor(Math.log10(d)); // p.ej. 41234 → 4
        BigDecimal step = BigDecimal.TEN.pow(Math.max(0, magnitude - 1)); // 2 cifras significativas
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    @Override
    @Transactional
    public Payment confirmSucceeded(UUID paymentId, Map<String, Object> providerPayload) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getStatus() == PaymentStatus.SUCCEEDED)
            return p;

        p.setStatus(PaymentStatus.SUCCEEDED);
        Map<String, Object> merged = new HashMap<>(
                p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.put("confirmed_at", Instant.now().toString());
        merged.putAll(providerPayload);
        p.setProviderResponse(merged);
        p = paymentRepository.save(p);

        boolean isOrderPayment = "ORDER_PAYMENT".equals(p.getPurpose()) && p.getOrderId() != null;
        if (isOrderPayment) {
            // El cobro externo (Stripe/PayPal/USDT) ya capturó el dinero. Marcamos
            // la orden como PAID para que el FulfillmentService la recoja.
            Order order = orderRepository.findById(p.getOrderId()).orElse(null);
            if (order != null && (order.getStatus() == OrderStatus.PENDING
                    || order.getStatus() == OrderStatus.AWAITING_PAYMENT)) {
                order.setStatus(OrderStatus.PAID);
                order = orderRepository.save(order);
                // Venta concretada: descontamos el stock de las variantes compradas (opción 2). Se hace
                // solo en esta transición (idempotente: una 2ª confirmación encuentra la orden ya PAID).
                stockService.deductForOrder(order);
            }
            auditLogger.log("order_payment.succeeded", p.getUserEmail(), Map.of("paymentId", p.getId(), "orderId",
                    p.getOrderId(), "method", p.getMethod(), "amount_usd_cents", p.getAmountUsdCents()));
            // Email de confirmación de pago + FACTURA al comprador.
            if (order != null) {
                String email = p.getUserEmail() != null ? p.getUserEmail()
                        : userRepository.findById(p.getUserId()).map(u -> u.getEmail()).orElse(null);
                String locale = userRepository.findById(p.getUserId()).map(u -> u.getLanguage()).orElse(null);
                orderEmailService.paymentConfirmed(order, email, locale,
                        p.getMethod() != null ? p.getMethod().name() : null, p.getSettlementCurrency());
            }
        } else {
            // Recarga de wallet: acreditar saldo.
            String idempKey = "deposit-" + p.getId();
            walletUseCase.deposit(p.getUserId(), p.getAmountUsdCents(), p.getId(), idempKey,
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
        Map<String, Object> merged = new HashMap<>(
                p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.putAll(providerPayload != null ? providerPayload : Map.of());
        p.setProviderResponse(merged);
        return paymentRepository.save(p);
    }

    @Override
    @Transactional
    public Payment capturePayPal(UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getMethod() != PaymentMethod.PAYPAL)
            throw new BusinessException("Not a PayPal payment");
        PaymentGateway gw = resolveGateway(PaymentMethod.PAYPAL);
        if (!(gw instanceof PayPalGateway pp)) {
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
    public Payment confirmOrderPayment(UUID orderId, UUID paymentId) {
        Payment p = getOrderPayment(orderId, paymentId);
        if (p.getStatus() == PaymentStatus.SUCCEEDED) {
            return p; // idempotente
        }
        // Proveedor deshabilitado / mock-mode: el providerRef lleva el prefijo sintético.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith("cs_mock_") || ref.startsWith("paypal_mock_") || ref.startsWith("pi_mock_");
        if (mock) {
            return confirmSucceeded(p.getId(), Map.of("mock_confirm", true, "orderId", orderId.toString()));
        }
        if (p.getMethod() == PaymentMethod.PAYPAL) {
            // El comprador ya aprobó la orden en PayPal; capturamos del lado servidor.
            return capturePayPal(p.getId());
        }
        if (p.getMethod() == PaymentMethod.CARD) {
            PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
            if (!(gw instanceof StripeGateway sg)) {
                throw new BusinessException("Stripe gateway not configured");
            }
            Map<String, Object> resp = sg.retrieveCheckoutSession(p.getProviderRef());
            String status = String.valueOf(resp.getOrDefault("status", ""));
            if ("paid".equals(status) || Boolean.TRUE.equals(resp.get("mock"))) {
                return confirmSucceeded(p.getId(), resp);
            }
            return markFailed(p.getId(), "Stripe session status: " + status, resp);
        }
        throw new BusinessException("Confirm not supported for method: " + p.getMethod());
    }

    @Override
    @Transactional
    public Payment refundOrderPayment(UUID orderId, UUID paymentId, long amountCents) {
        Payment p = getOrderPayment(orderId, paymentId);
        if (p.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new BusinessException("Only a succeeded payment can be refunded (status=" + p.getStatus() + ")");
        }
        Map<String, Object> pr = p.getProviderResponse() != null ? p.getProviderResponse() : Map.of();
        Map<String, Object> result;

        if (p.getMethod() == PaymentMethod.PAYPAL) {
            PaymentGateway gw = resolveGateway(PaymentMethod.PAYPAL);
            if (!(gw instanceof PayPalGateway pp)) {
                throw new BusinessException("PayPal gateway not configured");
            }
            String captureId = PayPalGateway.extractCaptureId(pr);
            if (captureId == null) {
                captureId = p.getProviderRef(); // fallback (mock)
            }
            result = pp.refund(captureId, amountCents);
            String status = String.valueOf(result.getOrDefault("status", ""));
            boolean ok = "COMPLETED".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)
                    || Boolean.TRUE.equals(result.get("mock"));
            if (!ok) {
                throw new BusinessException("PayPal refund failed: " + status);
            }
        } else if (p.getMethod() == PaymentMethod.CARD) {
            PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
            if (!(gw instanceof StripeGateway sg)) {
                throw new BusinessException("Stripe gateway not configured");
            }
            String paymentIntentId = String.valueOf(pr.getOrDefault("paymentIntent", p.getProviderRef()));
            result = sg.refund(paymentIntentId, amountCents);
            String status = String.valueOf(result.getOrDefault("status", ""));
            boolean ok = "succeeded".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)
                    || Boolean.TRUE.equals(result.get("mock"));
            if (!ok) {
                throw new BusinessException("Stripe refund failed: " + status);
            }
        } else {
            throw new BusinessException("Refund not supported for method: " + p.getMethod());
        }

        p.setStatus(PaymentStatus.REFUNDED);
        Map<String, Object> merged = new HashMap<>(pr);
        merged.put("refund", result);
        merged.put("refunded_at", Instant.now().toString());
        p.setProviderResponse(merged);
        p = paymentRepository.save(p);
        auditLogger.log("order_payment.refunded", p.getUserEmail(),
                Map.of("paymentId", p.getId(), "orderId", orderId, "method", p.getMethod(), "amountCents", amountCents));
        return p;
    }

    @Override
    @Transactional
    public Payment confirmMockRecharge(UUID userId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        // Propietario: no permitir confirmar el pago de otro usuario (no filtramos pagos ajenos → 404).
        if (p.getUserId() == null || !p.getUserId().equals(userId)) {
            throw new NotFoundException("Payment");
        }
        // SEGURIDAD: esta vía "mock" acredita el saldo SIN pasar por la pasarela real. Debe aceptar EXCLUSIVAMENTE
        // pagos sintéticos de mock-mode (providerRef con prefijo *_mock_). Un checkout REAL de Stripe/PayPal
        // (cs_test_/cs_live_/pi_...) que aún no se ha cobrado NUNCA se acredita aquí; de lo contrario cualquier
        // usuario iniciaría una recarga y la "confirmaría" gratis (dinero libre en producción). El pago real se
        // confirma solo con confirmRecharge, que verifica el estado en la pasarela.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith("cs_mock_") || ref.startsWith("paypal_mock_") || ref.startsWith("pi_mock_");
        if (!mock) {
            throw new BusinessException("PAYMENT_REQUIRES_REAL_CONFIRMATION",
                    "Esta recarga debe completarse en la pasarela de pago real");
        }
        return confirmSucceeded(p.getId(), Map.of("mock_confirm", true));
    }

    @Override
    @Transactional
    public Payment confirmRecharge(UUID userId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        if (p.getUserId() == null || !p.getUserId().equals(userId)) {
            throw new NotFoundException("Payment"); // no filtramos pagos ajenos
        }
        if (p.getStatus() == PaymentStatus.SUCCEEDED) {
            return p; // idempotente: el wallet ya se acreditó
        }
        // Proveedor deshabilitado / mock-mode: el providerRef lleva un prefijo sintético.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith("cs_mock_") || ref.startsWith("paypal_mock_") || ref.startsWith("pi_mock_");
        if (mock) {
            return confirmSucceeded(p.getId(), Map.of("mock_confirm", true));
        }
        if (p.getMethod() == PaymentMethod.PAYPAL) {
            return capturePayPal(p.getId()); // el usuario ya aprobó en PayPal; capturamos del lado servidor
        }
        if (p.getMethod() == PaymentMethod.CARD) {
            PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
            if (!(gw instanceof StripeGateway sg)) {
                throw new BusinessException("Stripe gateway not configured");
            }
            Map<String, Object> resp = sg.retrieveCheckoutSession(p.getProviderRef());
            String status = String.valueOf(resp.getOrDefault("status", ""));
            if ("paid".equals(status) || Boolean.TRUE.equals(resp.get("mock"))) {
                return confirmSucceeded(p.getId(), resp); // acredita el wallet (branch no-order)
            }
            throw new BusinessException("Payment not completed (status " + status + ")");
        }
        throw new BusinessException("Unsupported method for recharge confirm");
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
        return paymentJpaRepositoryAdapter.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
    }

    /* ============================================================
     *  Partner / customer order payment (CARD / PAYPAL / USDT / WALLET)
     * ============================================================ */

    @Override
    @Transactional
    public Payment initiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey) {
        if (method == null)
            throw new BusinessException("paymentMethod required");

        if (idempotencyKey != null) {
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent())
                return existing.get();
        }

        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }

        long amountUsdCents = order.getTotalCents();
        if (amountUsdCents < 100)
            throw new BusinessException("Order total below $1.00 USD — refusing to charge");

        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null)
            throw new BusinessException("Cannot resolve payer user for this order");
        if (userRepository.findById(payerUserId).isEmpty())
            throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);

        // Moneda de cobro: con Stripe (CARD) se cobra en EUR si el usuario navega en EUR; en cualquier
        // otra moneda se cobra el equivalente en USD. El resto de métodos liquidan en USD (o USDT).
        // El monto se calcula SUMANDO el precio por línea convertido a la moneda de cobro (2 decimales
        // hacia arriba por línea, igual que el carrito y el catálogo) — NO convirtiendo el total una vez,
        // para que lo cobrado coincida EXACTAMENTE con lo que el cliente vio en el carrito.
        String displayCcy = CurrencyHolder.get();
        boolean stripeEur = method == PaymentMethod.CARD && "EUR".equalsIgnoreCase(displayCcy);
        String settlementCcy = method == PaymentMethod.USDT ? "USDT" : (stripeEur ? "EUR" : "USD");
        BigDecimal settlementAmount = perLineSettlementAmount(order, settlementCcy);

        Payment p = Payment.builder().userId(payerUserId).walletId(wallet.getId()).method(method)
                .status(PaymentStatus.PENDING).amountUsdCents(amountUsdCents)
                .amountDisplay(BigDecimal.valueOf(amountUsdCents).movePointLeft(2))
                .currencyDisplay(displayCcy)
                .settlementCurrency(settlementCcy).settlementAmount(settlementAmount).idempotencyKey(idempotencyKey)
                .orderId(orderId).purpose("ORDER_PAYMENT").build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        var result = gw.initiate(managedEntity(p.getId()));

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        Map<String, Object> meta = result.raw() != null ? new HashMap<>(result.raw()) : new HashMap<>();
        if (result.clientSecret() != null)
            meta.put("clientSecret", result.clientSecret());
        if (result.approveUrl() != null)
            meta.put("approveUrl", result.approveUrl());
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

        auditLogger.log("order_payment.initiate", p.getUserEmail(),
                Map.of("orderId", orderId, "paymentId", p.getId(), "method", method, "amountCents", amountUsdCents));
        return p;
    }

    /**
     * Monto a cobrar en {@code ccy} = SUMA del precio por línea convertido a esa moneda (2 decimales hacia
     * arriba por línea, igual que el carrito y el catálogo) + el envío convertido. Así lo cobrado coincide
     * con el total que el cliente ve en el carrito (que también suma línea a línea), evitando el desfase de
     * céntimos de convertir el total una sola vez.
     */
    private BigDecimal perLineSettlementAmount(Order order, String ccy) {
        if ("USDT".equalsIgnoreCase(ccy)) {
            return BigDecimal.valueOf(order.getTotalCents()).movePointLeft(2);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem it : order.getItems()) {
            BigDecimal usdUnit = BigDecimal.valueOf(it.getUnitPriceCents()).movePointLeft(2);
            BigDecimal unit = currencyRateService.usdTo(usdUnit, ccy);
            sum = sum.add(unit.multiply(BigDecimal.valueOf(it.getQuantity())));
        }
        BigDecimal ship = currencyRateService.usdTo(BigDecimal.valueOf(order.getShippingCents()).movePointLeft(2), ccy);
        BigDecimal tax = currencyRateService.usdTo(BigDecimal.valueOf(order.getTaxCents()).movePointLeft(2), ccy);
        return sum.add(ship).add(tax);
    }

    @Override
    @Transactional
    public Payment chargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null)
            throw new BusinessException("Cannot resolve payer user for this order");

        long amountUsdCents = order.getTotalCents();
        if (userRepository.findById(payerUserId).isEmpty())
            throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);

        // El WalletUseCase.charge ya valida saldo y maneja idempotencia.
        walletUseCase.charge(payerUserId, amountUsdCents, orderId, idempotencyKey, "Order " + order.getOrderNumber());

        // Registramos el payment en SUCCEEDED para auditoría uniforme.
        Payment p = Payment.builder().userId(payerUserId).walletId(wallet.getId()).method(PaymentMethod.CARD) // sentinel: wallet no es un PaymentMethod del enum
                .status(PaymentStatus.SUCCEEDED).amountUsdCents(amountUsdCents)
                .amountDisplay(BigDecimal.valueOf(amountUsdCents).movePointLeft(2)).currencyDisplay("USD")
                .settlementCurrency("USD").provider("wallet").idempotencyKey(idempotencyKey).orderId(orderId)
                .purpose("ORDER_PAYMENT")
                .providerResponse(Map.of("walletId", wallet.getId().toString(), "settled", "atomic")).build();
        p = paymentRepository.save(p);

        // La orden pasa a PAID — el FulfillmentService la recogerá.
        order.setStatus(OrderStatus.PAID);
        order = orderRepository.save(order);

        auditLogger.log("order_payment.wallet", p.getUserEmail(),
                Map.of("orderId", orderId, "paymentId", p.getId(), "amountCents", amountUsdCents));
        // Email de confirmación de pago + FACTURA (pago con saldo del wallet).
        String email = userRepository.findById(payerUserId).map(u -> u.getEmail()).orElse(null);
        String locale = userRepository.findById(payerUserId).map(u -> u.getLanguage()).orElse(null);
        orderEmailService.paymentConfirmed(order, email, locale, "WALLET");
        return p;
    }

    @Override
    @Transactional
    public Payment initiateOrderPaymentView(UUID orderId, UUID userId, PaymentMethod method, boolean wallet,
            String idempotencyKey) {
        return wallet
                ? chargeWalletForOrder(orderId, userId, idempotencyKey)
                : initiateOrderPayment(orderId, userId, method, idempotencyKey);
    }

    @Override
    @Transactional
    public Payment initiatePartnerOrderPayment(Jwt jwt, UUID orderId, boolean wallet, PaymentMethod method,
            String idempotencyKey) {
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
    public Payment initiateMeOrderPayment(UUID userId, UUID orderId, boolean wallet, PaymentMethod method,
            String idempotencyKey) {
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
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment"));
        // El pago debe corresponder a la orden indicada (evita confirmar un pago de otra orden).
        if (p.getOrderId() == null || !p.getOrderId().equals(orderId)) {
            throw new NotFoundException("Payment");
        }
        // SEGURIDAD (idéntico a confirmMockRecharge): esta vía marca la orden como PAGADA SIN pasar por la
        // pasarela real. Solo se admite para pagos sintéticos de mock-mode (providerRef *_mock_). Un checkout
        // REAL de Stripe/PayPal (cs_test_/cs_live_/pi_...) no cobrado NUNCA se confirma aquí; de lo contrario
        // cualquiera crearía una orden con tarjeta y la marcaría PAGADA gratis (y se enviaría la mercancía).
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith("cs_mock_") || ref.startsWith("paypal_mock_") || ref.startsWith("pi_mock_");
        if (!mock) {
            throw new BusinessException("PAYMENT_REQUIRES_REAL_CONFIRMATION",
                    "Este pago debe completarse en la pasarela de pago real");
        }
        return confirmSucceeded(p.getId(), Map.of("mock_confirm", true, "orderId", orderId.toString()));
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
                if (p != null)
                    paymentIdStr = p.getId().toString();
            }

            if ("customer.subscription.created".equals(eventType) || "customer.subscription.updated".equals(eventType)
                    || "customer.subscription.deleted".equals(eventType)) {
                String stripeSubId = String.valueOf(data.get("id"));
                String stripeStatus = String.valueOf(data.get("status"));
                // Sincroniza la CustomerSubscription local (estado, fin de periodo, cancelación) + el tier
                // del partner. Cubre renovación, fallo de cobro (PAST_DUE) y cancelación desde Stripe.
                customerSubscriptionUseCase.syncFromStripe(stripeSubId, stripeStatus,
                        asEpoch(data.get("current_period_end")), asEpoch(data.get("cancel_at")));
                partnerPlanSyncService.onSubscriptionEvent(stripeSubId, stripeStatus, eventType);
                return "ok";
            }

            if ("invoice.payment_failed".equals(eventType)) {
                // Fallo de cobro recurrente de la suscripción: la factura lleva el id de la suscripción de
                // Stripe. Avisamos al dueño (in-app + email) para que revise/actualice su método de pago.
                subscriptionNotificationService.planPaymentFailed(String.valueOf(data.get("subscription")));
                return "ok";
            }

            if (paymentIdStr == null)
                return "no-match";
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

    /** Convierte un valor JSON (Number/String/null) a epoch-segundos Long, o null. */
    private static Long asEpoch(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
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
            if (p == null)
                return "no-match";
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
            if (p == null)
                return "no-match";
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
