package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.exception.WebhookProcessingException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
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
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.stripe.exception.StripeException;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
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
import java.util.Optional;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String AMOUNT_USD_CENTS = "amount_usd_cents";
    private static final String ORDER_PAYMENT = "ORDER_PAYMENT";
    private static final String MOCK_CONFIRM = "mock_confirm";
    private static final String PAYPAL_MOCK = "paypal_mock_";
    private static final String AMOUNTCENTS = "amountCents";
    private static final String PAYMENTID = "paymentId";
    private static final String NO_MATCH = "no-match";
    private static final String PI_MOCK = "pi_mock_";
    private static final String CS_MOCK = "cs_mock_";
    private static final String PAYMENT = "Payment";
    private static final String ORDERID = "orderId";
    private static final String METHOD = "method";
    private static final String STATUS = "status";

    private final List<PaymentGateway> gateways;
    private final PaymentRepository paymentRepository;
    private final PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    /** Perfiles Spring activos: se usa para prohibir los pagos simulados en pro/pre (fail-closed). */
    @Value("${spring.profiles.active:}")
    private String activeProfiles;
    private final WalletUseCase walletUseCase;
    private final StripeService stripeService;
    private final AuditLogger auditLogger;
    private final PartnerPlanSyncService partnerPlanSyncService;
    private final CustomerSubscriptionUseCase customerSubscriptionUseCase;
    private final SubscriptionNotificationService subscriptionNotificationService;
    private final ObjectMapper objectMapper;
    private final OrderEmailService orderEmailService;
    private final CurrencyRateService currencyRateService;
    /** La cuenta del pedido, compartida con el checkout, la ficha del cliente y el panel. */
    private final OrderAmounts orderAmounts;
    private final StockService stockService;
    /** Al cobrar hay que dejar anotado qué comprar en 1688 y a qué proveedor. */
    private final SupplierPurchaseService supplierPurchaseService;
    /** Avisos al responsable cuando una pasarela deja de cobrar. */
    private final OpsAlertService opsAlertService;

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
        Optional<Payment> existing = existingByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Returning existing payment for idempotency-key={}", idempotencyKey);
            return existing.get();
        }

        if (userRepository.findById(userId).isEmpty())
            throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(userId);

        // Moneda de cobro de la recarga: MISMA lógica que el checkout. Con Stripe (CARD) se cobra en EUR si
        // el usuario trabaja la web en EUR; en cualquier otra divisa se cobra el equivalente en USD. PayPal
        // liquida en USD y USDT en USDT. El saldo del wallet SIEMPRE se acredita en USD canónico.
        String displayCcy = currencyDisplay != null && !currencyDisplay.isBlank() ? currencyDisplay : "USD";
        boolean stripeEur = method == PaymentMethod.CARD && "EUR".equalsIgnoreCase(displayCcy);
        String settlementCcy = settlementCurrencyFor(method, stripeEur);
        BigDecimal settlementAmount = rechargeSettlementAmount(settlementCcy, displayCcy, usdCents, amountDisplay);

        Payment p = Payment.builder().userId(userId).walletId(wallet.getId()).method(method)
                .status(PaymentStatus.PENDING).amountDisplay(amountDisplay).currencyDisplay(currencyDisplay)
                .amountUsdCents(usdCents).settlementCurrency(settlementCcy).settlementAmount(settlementAmount)
                .idempotencyKey(idempotencyKey).build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        PaymentGateway.InitiateResult result = initiateOrAlert(gw, p.getId(), "recarga de saldo");

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        p.setProviderResponse(result.raw() != null ? result.raw() : new HashMap<>());
        applyCryptoDetails(p, result);
        p.setStatus(PaymentStatus.REQUIRES_ACTION);
        p = paymentRepository.save(p);

        auditLogger.log("payment.initiate", p.getUserEmail(),
                Map.of(PAYMENTID, p.getId(), METHOD, method, AMOUNT_USD_CENTS, usdCents));

        // attach client metadata to provider_response so the controller can return it
        Map<String, Object> meta = new HashMap<>(p.getProviderResponse());
        putRedirectMetadata(meta, result);
        putCryptoMetadata(meta, result, p.getCryptoExpiresAt());
        p.setProviderResponse(meta);
        return paymentRepository.save(p);
    }

    /**
     * Moneda en la que se liquida el cobro. El orden de comprobación es el importante: USDT manda sobre
     * todo lo demás (la cripto se liquida en su propia moneda); solo después se mira si Stripe puede
     * cobrar en EUR. Cualquier otro caso liquida en USD, la divisa canónica del sistema.
     */
    private static String settlementCurrencyFor(PaymentMethod method, boolean stripeEur) {
        if (method == PaymentMethod.USDT) {
            return "USDT";
        }
        return stripeEur ? "EUR" : "USD";
    }

    /** Pago ya creado con esa Idempotency-Key, si lo hay. Sin clave no hay nada que reutilizar. */
    private Optional<Payment> existingByIdempotencyKey(String idempotencyKey) {
        return idempotencyKey == null ? Optional.empty() : paymentRepository.findByIdempotencyKey(idempotencyKey);
    }

    /** Fija en el pago la dirección cripto que devolvió la pasarela y su vencimiento (30 min). */
    private static void applyCryptoDetails(Payment p, PaymentGateway.InitiateResult result) {
        if (result.cryptoAddress() == null) {
            return;
        }
        p.setCryptoAddress(result.cryptoAddress());
        p.setCryptoChain(result.cryptoChain());
        p.setQrUrl(result.qrUrl());
        p.setCryptoExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
    }

    /** Datos que el front necesita para terminar el pago fuera de la app (secret de Stripe, URL de PayPal). */
    private static void putRedirectMetadata(Map<String, Object> meta, PaymentGateway.InitiateResult result) {
        if (result.clientSecret() != null) {
            meta.put("clientSecret", result.clientSecret());
        }
        if (result.approveUrl() != null) {
            meta.put("approveUrl", result.approveUrl());
        }
    }

    /** Datos de la dirección cripto (solo USDT); {@code expiresAt} es el vencimiento ya fijado en el pago. */
    private static void putCryptoMetadata(Map<String, Object> meta, PaymentGateway.InitiateResult result,
            Instant expiresAt) {
        if (result.cryptoAddress() == null) {
            return;
        }
        meta.put("cryptoAddress", result.cryptoAddress());
        meta.put("cryptoChain", result.cryptoChain());
        meta.put("qrUrl", result.qrUrl());
        meta.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
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
        return doConfirmSucceeded(paymentId, providerPayload);
    }

    /**
     * Cuerpo de la confirmación, deliberadamente sin anotar.
     *
     * <p>Aquí dentro (webhooks, captura de PayPal, confirmación de un pago mock…) se llamaba a
     * {@code this.confirmSucceeded(...)}: una invocación directa NO pasa por el proxy de Spring, así que
     * la {@code @Transactional} del método invocado nunca se aplicaba —era una promesa que nadie cumplía
     * (java:S6809)—. Con la anotación solo en el punto de entrada público, la transacción está donde de
     * verdad actúa y el resto del cobro entra por aquí. Mismo criterio en el resto de métodos {@code do…}
     * y en {@link #requireOrderPayment(UUID, UUID)} de esta clase.
     */
    private Payment doConfirmSucceeded(UUID paymentId, Map<String, Object> providerPayload) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        if (p.getStatus() == PaymentStatus.SUCCEEDED)
            return p;

        p.setStatus(PaymentStatus.SUCCEEDED);
        Map<String, Object> merged = new HashMap<>(
                p.getProviderResponse() != null ? p.getProviderResponse() : Map.of());
        merged.put("confirmed_at", Instant.now().toString());
        merged.putAll(providerPayload);
        p.setProviderResponse(merged);
        p = paymentRepository.save(p);

        boolean isOrderPayment = ORDER_PAYMENT.equals(p.getPurpose()) && p.getOrderId() != null;
        if (isOrderPayment) {
            settleOrderPayment(p);
        } else {
            creditWalletRecharge(p);
        }
        return p;
    }

    /**
     * El cobro externo (Stripe/PayPal/USDT) ya capturó el dinero: marcamos la orden como PAID para que el
     * FulfillmentService la recoja, descontamos existencias y enviamos la factura.
     *
     * <p>El paso a PAID solo se da desde PENDING/AWAITING_PAYMENT. Así una segunda confirmación (webhook
     * duplicado o reproceso) encuentra la orden ya avanzada, no la hace retroceder y NO vuelve a descontar
     * stock; el audit y el email, en cambio, se emiten aunque la orden ya estuviera pagada.
     */
    private void settleOrderPayment(Payment p) {
        Order order = orderRepository.findById(p.getOrderId()).orElse(null);
        if (order != null && (order.getStatus() == OrderStatus.PENDING
                || order.getStatus() == OrderStatus.AWAITING_PAYMENT)) {
            order.setStatus(OrderStatus.PAID);
            order = orderRepository.save(order);
            stockService.deductForOrder(order);
        }
        // El dinero ya está cobrado: hay que comprar la mercancía en 1688. Se planifica aunque el pedido
        // ya estuviera pagado (webhook duplicado o reproceso) porque planPurchases es idempotente y así
        // un fallo transitorio en el primer intento se recupera solo en el siguiente.
        if (order != null && order.getStatus() == OrderStatus.PAID) {
            supplierPurchaseService.planPurchases(order);
        }
        auditLogger.log("order_payment.succeeded", p.getUserEmail(), Map.of(PAYMENTID, p.getId(), ORDERID,
                p.getOrderId(), METHOD, p.getMethod(), AMOUNT_USD_CENTS, p.getAmountUsdCents()));
        if (order != null) {
            sendPaymentConfirmedEmail(p, order);
        }
    }

    /** Email de confirmación de pago + FACTURA al comprador. */
    private void sendPaymentConfirmedEmail(Payment p, Order order) {
        String email = p.getUserEmail() != null ? p.getUserEmail()
                : userRepository.findById(p.getUserId()).map(u -> u.getEmail()).orElse(null);
        String locale = userRepository.findById(p.getUserId()).map(u -> u.getLanguage()).orElse(null);
        orderEmailService.paymentConfirmed(order, email, locale,
                p.getMethod() != null ? p.getMethod().name() : null, p.getSettlementCurrency());
    }

    /** Recarga de wallet: acreditar saldo. */
    private void creditWalletRecharge(Payment p) {
        String idempKey = "deposit-" + p.getId();
        walletUseCase.deposit(p.getUserId(), p.getAmountUsdCents(), p.getId(), idempKey,
                "Wallet recharge via " + p.getMethod());
        auditLogger.log("payment.succeeded", p.getUserEmail(),
                Map.of(PAYMENTID, p.getId(), METHOD, p.getMethod(), AMOUNT_USD_CENTS, p.getAmountUsdCents()));
    }

    @Override
    @Transactional
    public Payment markFailed(UUID paymentId, String errorMessage, Map<String, Object> providerPayload) {
        return doMarkFailed(paymentId, errorMessage, providerPayload);
    }

    private Payment doMarkFailed(UUID paymentId, String errorMessage, Map<String, Object> providerPayload) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
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
    public Payment capturePayPal(UUID userId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        assertPaymentOwnedBy(p, userId); // IDOR: solo el dueño del pago puede capturarlo
        return doCapturePayPal(paymentId);
    }

    private Payment doCapturePayPal(UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        if (p.getMethod() != PaymentMethod.PAYPAL)
            throw new BusinessException("Not a PayPal payment");
        PaymentGateway gw = resolveGateway(PaymentMethod.PAYPAL);
        if (!(gw instanceof PayPalGateway pp)) {
            throw new BusinessException("PayPal gateway not configured");
        }
        Map<String, Object> resp = pp.capture(p.getProviderRef());
        String status = String.valueOf(resp.getOrDefault(STATUS, ""));
        if ("COMPLETED".equalsIgnoreCase(status) || Boolean.TRUE.equals(resp.get("mock"))) {
            return doConfirmSucceeded(p.getId(), resp);
        }
        return doMarkFailed(p.getId(), "PayPal capture returned " + status, resp);
    }

    @Override
    @Transactional
    public Payment confirmOrderPayment(UUID userId, UUID orderId, UUID paymentId) {
        Payment p = requireOrderPayment(orderId, paymentId);
        assertPaymentOwnedBy(p, userId);
        if (p.getStatus() == PaymentStatus.SUCCEEDED) {
            return p; // idempotente
        }
        // Proveedor deshabilitado / mock-mode: el providerRef lleva el prefijo sintético.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith(CS_MOCK) || ref.startsWith(PAYPAL_MOCK) || ref.startsWith(PI_MOCK);
        if (mock) {
            // FAIL-CLOSED: marcar un pedido como pagado por vía sintética (sin pasarela real) SOLO fuera de
            // pro/pre. Evita que, si prod arranca con pagos deshabilitados, se confirmen pedidos gratis.
            assertMockAllowed();
            return doConfirmSucceeded(p.getId(), Map.of(MOCK_CONFIRM, true, ORDERID, orderId.toString()));
        }
        if (p.getMethod() == PaymentMethod.PAYPAL) {
            // El comprador ya aprobó la orden en PayPal; capturamos del lado servidor.
            return doCapturePayPal(p.getId());
        }
        if (p.getMethod() == PaymentMethod.CARD) {
            PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
            if (!(gw instanceof StripeGateway sg)) {
                throw new BusinessException("Stripe gateway not configured");
            }
            Map<String, Object> resp = sg.retrieveCheckoutSession(p.getProviderRef());
            String status = String.valueOf(resp.getOrDefault(STATUS, ""));
            if ("paid".equals(status) || Boolean.TRUE.equals(resp.get("mock"))) {
                return doConfirmSucceeded(p.getId(), resp);
            }
            return doMarkFailed(p.getId(), "Stripe session status: " + status, resp);
        }
        throw new BusinessException("Confirm not supported for method: " + p.getMethod());
    }

    @Override
    @Transactional
    public Payment refundOrderPayment(UUID orderId, UUID paymentId, long amountCents) {
        Payment p = requireOrderPayment(orderId, paymentId);
        if (p.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new BusinessException("Only a succeeded payment can be refunded (status=" + p.getStatus() + ")");
        }
        Map<String, Object> pr = p.getProviderResponse() != null ? p.getProviderResponse() : Map.of();
        Map<String, Object> result;

        if (p.getMethod() == PaymentMethod.PAYPAL) {
            result = refundWithPayPal(p, pr, amountCents);
        } else if (p.getMethod() == PaymentMethod.CARD) {
            result = refundWithStripe(p, pr, amountCents);
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
                Map.of(PAYMENTID, p.getId(), ORDERID, orderId, METHOD, p.getMethod(), AMOUNTCENTS, amountCents));
        return p;
    }

    /**
     * Devolución por PayPal. Se reembolsa sobre la CAPTURA, no sobre la orden: si la respuesta guardada no
     * trae el id de captura (pagos simulados) se cae al providerRef. Un estado que no sea COMPLETED ni
     * PENDING aborta con excepción para que el pago NO se marque como devuelto sin que PayPal lo confirme.
     */
    private Map<String, Object> refundWithPayPal(Payment p, Map<String, Object> providerResponse, long amountCents) {
        PaymentGateway gw = resolveGateway(PaymentMethod.PAYPAL);
        if (!(gw instanceof PayPalGateway pp)) {
            throw new BusinessException("PayPal gateway not configured");
        }
        String captureId = PayPalGateway.extractCaptureId(providerResponse);
        if (captureId == null) {
            captureId = p.getProviderRef(); // fallback (mock)
        }
        Map<String, Object> result = pp.refund(captureId, amountCents);
        String status = String.valueOf(result.getOrDefault(STATUS, ""));
        boolean ok = "COMPLETED".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)
                || Boolean.TRUE.equals(result.get("mock"));
        if (!ok) {
            throw new BusinessException("PayPal refund failed: " + status);
        }
        return result;
    }

    /**
     * Devolución por Stripe: se reembolsa el PaymentIntent guardado en la respuesta del proveedor (con el
     * providerRef como respaldo). Igual que en PayPal, un estado distinto de succeeded/pending aborta para
     * no dar por devuelto un dinero que Stripe no ha devuelto.
     */
    private Map<String, Object> refundWithStripe(Payment p, Map<String, Object> providerResponse, long amountCents) {
        PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
        if (!(gw instanceof StripeGateway sg)) {
            throw new BusinessException("Stripe gateway not configured");
        }
        String paymentIntentId = String.valueOf(providerResponse.getOrDefault("paymentIntent", p.getProviderRef()));
        Map<String, Object> result = sg.refund(paymentIntentId, amountCents);
        String status = String.valueOf(result.getOrDefault(STATUS, ""));
        boolean ok = "succeeded".equalsIgnoreCase(status) || "pending".equalsIgnoreCase(status)
                || Boolean.TRUE.equals(result.get("mock"));
        if (!ok) {
            throw new BusinessException("Stripe refund failed: " + status);
        }
        return result;
    }

    @Override
    @Transactional
    public Payment confirmMockRecharge(UUID userId, UUID paymentId) {
        assertMockAllowed();
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        // Propietario: no permitir confirmar el pago de otro usuario (no filtramos pagos ajenos → 404).
        if (p.getUserId() == null || !p.getUserId().equals(userId)) {
            throw new NotFoundException(PAYMENT);
        }
        // SEGURIDAD: esta vía "mock" acredita el saldo SIN pasar por la pasarela real. Debe aceptar EXCLUSIVAMENTE
        // pagos sintéticos de mock-mode (providerRef con prefijo *_mock_). Un checkout REAL de Stripe/PayPal
        // (cs_test_/cs_live_/pi_...) que aún no se ha cobrado NUNCA se acredita aquí; de lo contrario cualquier
        // usuario iniciaría una recarga y la "confirmaría" gratis (dinero libre en producción). El pago real se
        // confirma solo con confirmRecharge, que verifica el estado en la pasarela.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith(CS_MOCK) || ref.startsWith(PAYPAL_MOCK) || ref.startsWith(PI_MOCK);
        if (!mock) {
            throw new BusinessException("PAYMENT_REQUIRES_REAL_CONFIRMATION",
                    "Esta recarga debe completarse en la pasarela de pago real");
        }
        return doConfirmSucceeded(p.getId(), Map.of(MOCK_CONFIRM, true));
    }

    @Override
    @Transactional
    public Payment confirmRecharge(UUID userId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        if (p.getUserId() == null || !p.getUserId().equals(userId)) {
            throw new NotFoundException(PAYMENT); // no filtramos pagos ajenos
        }
        if (p.getStatus() == PaymentStatus.SUCCEEDED) {
            return p; // idempotente: el wallet ya se acreditó
        }
        // Proveedor deshabilitado / mock-mode: el providerRef lleva un prefijo sintético.
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith(CS_MOCK) || ref.startsWith(PAYPAL_MOCK) || ref.startsWith(PI_MOCK);
        if (mock) {
            // FAIL-CLOSED: acreditar un pago sintético (sin pasarela real) SOLO se permite fuera de pro/pre.
            // Si prod arranca con las pasarelas deshabilitadas, initiate() genera refs mock; sin este guard
            // cualquiera "confirmaría" una recarga y tendría saldo gratis. En pro/pre esto lanza excepción.
            assertMockAllowed();
            return doConfirmSucceeded(p.getId(), Map.of(MOCK_CONFIRM, true));
        }
        if (p.getMethod() == PaymentMethod.PAYPAL) {
            return doCapturePayPal(p.getId()); // el usuario ya aprobó en PayPal; capturamos del lado servidor
        }
        if (p.getMethod() == PaymentMethod.CARD) {
            PaymentGateway gw = resolveGateway(PaymentMethod.CARD);
            if (!(gw instanceof StripeGateway sg)) {
                throw new BusinessException("Stripe gateway not configured");
            }
            Map<String, Object> resp = sg.retrieveCheckoutSession(p.getProviderRef());
            String status = String.valueOf(resp.getOrDefault(STATUS, ""));
            if ("paid".equals(status) || Boolean.TRUE.equals(resp.get("mock"))) {
                return doConfirmSucceeded(p.getId(), resp); // acredita el wallet (branch no-order)
            }
            throw new BusinessException("Payment not completed (status " + status + ")");
        }
        throw new BusinessException("Unsupported method for recharge confirm");
    }

    @Override
    @Transactional(readOnly = true)
    public Payment find(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new NotFoundException(PAYMENT));
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

    /**
     * Arranca el pago en la pasarela y, si falla, avisa al responsable antes de propagar el error.
     *
     * <p>Un fallo aquí no lo puede resolver el cliente: significa que ese método de pago no está
     * cobrando. Sin aviso, solo se detecta cuando alguien reclama o mirando los logs, y mientras tanto
     * se pierden ventas. El error se relanza tal cual para que el flujo de pago siga comportándose igual.
     */
    private PaymentGateway.InitiateResult initiateOrAlert(PaymentGateway gw, UUID paymentId, String operation) {
        try {
            return gw.initiate(managedEntity(paymentId));
        } catch (RuntimeException e) {
            opsAlertService.paymentFailed(gw.providerName(), operation, paymentId.toString(),
                    e.getMessage() != null ? e.getMessage() : e.toString());
            throw e;
        }
    }

    /** Fetches the managed entity for the gateway call (gateways read the persisted id + user). */
    private PaymentEntity managedEntity(UUID paymentId) {
        return paymentJpaRepositoryAdapter.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
    }

    /* ============================================================
     *  Partner / customer order payment (CARD / PAYPAL / USDT / WALLET)
     * ============================================================ */

    @Override
    @Transactional
    public Payment initiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey) {
        return doInitiateOrderPayment(orderId, userId, method, idempotencyKey);
    }

    private Payment doInitiateOrderPayment(UUID orderId, UUID userId, PaymentMethod method, String idempotencyKey) {
        if (method == null)
            throw new BusinessException("paymentMethod required");

        Optional<Payment> existing = existingByIdempotencyKey(idempotencyKey);
        if (existing.isPresent())
            return existing.get();

        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        assertOrderOwnedBy(order, userId);
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }
        // Un pedido ya PAGADO no se vuelve a cobrar (evita doble cargo al reintentar con otra clave idem).
        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessException("Order is already PAID");
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
        String settlementCcy = settlementCurrencyFor(method, stripeEur);
        BigDecimal settlementAmount = perLineSettlementAmount(order, settlementCcy);

        Payment p = Payment.builder().userId(payerUserId).walletId(wallet.getId()).method(method)
                .status(PaymentStatus.PENDING).amountUsdCents(amountUsdCents)
                // amountDisplay va emparejado con currencyDisplay: dejar aquí el importe en USD mientras
                // la divisa dice EUR hacía que el pago declarase 10,87 € cuando a la pasarela iban 9,54 €.
                // Lo cobrado siempre fue correcto; el dato publicado contradecía a la pasarela.
                .amountDisplay(perLineSettlementAmount(order, displayCcy))
                .currencyDisplay(displayCcy)
                .settlementCurrency(settlementCcy).settlementAmount(settlementAmount).idempotencyKey(idempotencyKey)
                .orderId(orderId).purpose(ORDER_PAYMENT).build();
        p = paymentRepository.save(p);

        PaymentGateway gw = resolveGateway(method);
        PaymentGateway.InitiateResult result = initiateOrAlert(gw, p.getId(), "cobro del pedido");

        p.setProvider(gw.providerName());
        p.setProviderRef(result.providerRef());
        Map<String, Object> meta = result.raw() != null ? new HashMap<>(result.raw()) : new HashMap<>();
        putRedirectMetadata(meta, result);
        // El vencimiento se fija en el pago ANTES de copiarlo a la metadata: el front y el registro deben
        // publicar exactamente el mismo instante, no dos "ahora + 30 min" calculados por separado.
        applyCryptoDetails(p, result);
        putCryptoMetadata(meta, result, p.getCryptoExpiresAt());
        p.setProviderResponse(meta);
        p.setStatus(PaymentStatus.REQUIRES_ACTION);
        p = paymentRepository.save(p);

        auditLogger.log("order_payment.initiate", p.getUserEmail(),
                Map.of(ORDERID, orderId, PAYMENTID, p.getId(), METHOD, method, AMOUNTCENTS, amountUsdCents));
        return p;
    }

    /**
     * Monto a cobrar en {@code ccy} = SUMA del precio por línea convertido a esa moneda (2 decimales hacia
     * arriba por línea, igual que el carrito y el catálogo) + el envío convertido. Así lo cobrado coincide
     * con el total que el cliente ve en el carrito (que también suma línea a línea), evitando el desfase de
     * céntimos de convertir el total una sola vez.
     */
    private BigDecimal perLineSettlementAmount(Order order, String ccy) {
        // USDT cobra el total canónico en dólares tal cual, sin conversión.
        if ("USDT".equalsIgnoreCase(ccy)) {
            return BigDecimal.valueOf(order.getTotalCents()).movePointLeft(2);
        }
        // La cuenta la hace OrderAmounts, que es donde vive para todos: el resumen del checkout, la ficha
        // del cliente, el panel y este cobro. Cuando cada uno la hacía por su cuenta se desviaron, y aquí
        // faltaba restar el descuento de referido: al cliente se le cobraba el descuento que se le acababa
        // de enseñar en pantalla.
        return orderAmounts.totalOf(order, ccy);
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public SavedCardPayResult payOrderWithSavedCard(UUID userId, UUID orderId, String paymentMethodId,
            String idempotencyKey) throws StripeException {
        if (!stripeService.isEnabled()) {
            throw new BusinessException("Los pagos con tarjeta no están activos en este entorno.");
        }
        Optional<Payment> existing = existingByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment p0 = existing.get();
            return new SavedCardPayResult(p0.getStatus() == PaymentStatus.SUCCEEDED ? "succeeded" : "pending", null,
                    p0.getId());
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        assertOrderOwnedBy(order, userId);
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return new SavedCardPayResult("succeeded", null, null);
        }
        long amountUsdCents = order.getTotalCents();
        if (amountUsdCents < 100) {
            throw new BusinessException("Order total below $1.00 USD — refusing to charge");
        }
        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        UserEntity user = userRepository.findById(payerUserId).orElseThrow(() -> new NotFoundException("User"));
        String customerId = user.getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException("No hay ninguna tarjeta guardada para cobrar.");
        }
        // IDOR: la tarjeta debe pertenecer al Customer de Stripe del usuario.
        boolean owned = stripeService.listCards(customerId).stream().anyMatch(pm -> pm.getId().equals(paymentMethodId));
        if (!owned) {
            throw new NotFoundException("Tarjeta no encontrada para el usuario");
        }
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);
        String displayCcy = CurrencyHolder.get();
        boolean stripeEur = "EUR".equalsIgnoreCase(displayCcy);
        String settlementCcy = settlementCurrencyFor(PaymentMethod.CARD, stripeEur);
        BigDecimal settlementAmount = perLineSettlementAmount(order, settlementCcy);
        long minor = settlementAmount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();

        Payment p = Payment.builder().userId(payerUserId).walletId(wallet.getId()).method(PaymentMethod.CARD)
                .status(PaymentStatus.PENDING).amountUsdCents(amountUsdCents)
                .amountDisplay(perLineSettlementAmount(order, displayCcy)).currencyDisplay(displayCcy)
                .settlementCurrency(settlementCcy).settlementAmount(settlementAmount).idempotencyKey(idempotencyKey)
                .orderId(orderId).purpose(ORDER_PAYMENT).provider("stripe").build();
        p = paymentRepository.save(p);

        StripeService.OffSessionResult r = stripeService.chargeSavedCardOffSession(customerId, paymentMethodId, minor,
                settlementCcy, orderId.toString());
        p.setProviderRef(r.id());
        paymentRepository.save(p);

        if ("succeeded".equals(r.status())) {
            // Éxito inmediato (sin 3DS): reutiliza la liquidación estándar → marca el pedido PAGADO.
            doConfirmSucceeded(p.getId(), Map.of("stripe_payment_intent", r.id(), "off_session", true));
            return new SavedCardPayResult("succeeded", null, p.getId());
        }
        // La tarjeta exige autenticación (3DS): el navegador la completa con el client_secret y luego confirma.
        return new SavedCardPayResult("requires_action", r.clientSecret(), p.getId());
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public Payment confirmSavedCardPayment(UUID userId, UUID orderId, UUID paymentId) throws StripeException {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        if (p.getUserId() == null || !p.getUserId().equals(userId) || !orderId.equals(p.getOrderId())) {
            throw new NotFoundException(PAYMENT); // no filtramos pagos ajenos
        }
        if (p.getStatus() == PaymentStatus.SUCCEEDED) {
            return p;
        }
        String status = stripeService.paymentIntentStatus(p.getProviderRef());
        if ("succeeded".equals(status)) {
            return doConfirmSucceeded(p.getId(), Map.of("stripe_payment_intent", p.getProviderRef(), "confirmed_3ds",
                    true));
        }
        throw new BusinessException("El pago con tarjeta no se completó (estado " + status + ")");
    }

    @Override
    @Transactional
    public Payment chargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey) {
        return doChargeWalletForOrder(orderId, userId, idempotencyKey);
    }

    private Payment doChargeWalletForOrder(UUID orderId, UUID userId, String idempotencyKey) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        assertOrderOwnedBy(order, userId);
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.REFUNDED) {
            throw new BusinessException("Order is " + order.getStatus() + " and cannot be paid");
        }
        // Un pedido ya PAGADO no se vuelve a cobrar (evita doble débito del wallet con otra clave idem).
        if (order.getStatus() == OrderStatus.PAID) {
            throw new BusinessException("Order is already PAID");
        }
        UUID payerUserId = order.getUserId() != null ? order.getUserId() : userId;
        if (payerUserId == null)
            throw new BusinessException("Cannot resolve payer user for this order");

        long amountUsdCents = order.getTotalCents();
        if (userRepository.findById(payerUserId).isEmpty())
            throw new NotFoundException("User");
        Wallet wallet = walletUseCase.getOrCreate(payerUserId);

        // El WalletUseCase.charge ya valida saldo y maneja idempotencia. La clave del cargo va ACOTADA AL
        // PEDIDO ("order-charge-<orderId>"), no la del cliente: así dos peticiones concurrentes al MISMO
        // pedido con claves idem distintas deduplican en el wallet y solo se debita UNA vez (el guard PAID
        // solo cubre el caso secuencial).
        String walletKey = "order-charge-" + orderId;
        walletUseCase.charge(payerUserId, amountUsdCents, orderId, walletKey, "Order " + order.getOrderNumber());

        // Registramos el payment en SUCCEEDED para auditoría uniforme.
        Payment p = Payment.builder().userId(payerUserId).walletId(wallet.getId()).method(PaymentMethod.CARD) // sentinel: wallet no es un PaymentMethod del enum
                .status(PaymentStatus.SUCCEEDED).amountUsdCents(amountUsdCents)
                .amountDisplay(BigDecimal.valueOf(amountUsdCents).movePointLeft(2)).currencyDisplay("USD")
                .settlementCurrency("USD").provider("wallet").idempotencyKey(idempotencyKey).orderId(orderId)
                .purpose(ORDER_PAYMENT)
                .providerResponse(Map.of("walletId", wallet.getId().toString(), "settled", "atomic")).build();
        p = paymentRepository.save(p);

        // La orden pasa a PAID — el FulfillmentService la recogerá.
        order.setStatus(OrderStatus.PAID);
        order = orderRepository.save(order);
        supplierPurchaseService.planPurchases(order);

        auditLogger.log("order_payment.wallet", p.getUserEmail(),
                Map.of(ORDERID, orderId, PAYMENTID, p.getId(), AMOUNTCENTS, amountUsdCents));
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
        return doInitiateOrderPaymentView(orderId, userId, method, wallet, idempotencyKey);
    }

    private Payment doInitiateOrderPaymentView(UUID orderId, UUID userId, PaymentMethod method, boolean wallet,
            String idempotencyKey) {
        return wallet
                ? doChargeWalletForOrder(orderId, userId, idempotencyKey)
                : doInitiateOrderPayment(orderId, userId, method, idempotencyKey);
    }

    @Override
    @Transactional
    public Payment initiatePartnerOrderPayment(Jwt jwt, UUID orderId, boolean wallet, PaymentMethod method,
            String idempotencyKey) {
        // Cross-tenant: el pedido DEBE pertenecer a este partner. assertOrderOwnedBy tolera userId==null (los
        // pedidos de partner no tienen userId), así que sin esta comprobación un partner podía pagar/forzar a
        // PAID el pedido de OTRO partner conociendo su UUID.
        assertOrderOwnedByPartner(jwt, orderId);
        UUID userId = resolvePartnerUserId(jwt);
        return doInitiateOrderPaymentView(orderId, userId, method, wallet, idempotencyKey);
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
        return doInitiateOrderPaymentView(orderId, userId, method, wallet, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> listOrderPayments(UUID orderId) {
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Payment> listOrderPaymentsForPartner(Jwt jwt, UUID orderId) {
        assertOrderOwnedByPartner(jwt, orderId);
        return listOrderPayments(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getOrderPayment(UUID orderId, UUID paymentId) {
        return requireOrderPayment(orderId, paymentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Payment getOrderPaymentForPartner(Jwt jwt, UUID orderId, UUID paymentId) {
        assertOrderOwnedByPartner(jwt, orderId);
        return requireOrderPayment(orderId, paymentId);
    }

    /**
     * IDOR entre partners: un partner solo puede leer los pagos de SUS pedidos. El pedido debe pertenecer al
     * partner del JWT (partnerAppId == id derivado del subject); si es de otro partner o del escaparate
     * (partnerAppId null), 404 — sin filtrar su existencia.
     */
    private void assertOrderOwnedByPartner(Jwt jwt, UUID orderId) {
        UUID partnerId = resolvePartnerUserId(jwt);
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order"));
        if (order.getPartnerAppId() == null || !order.getPartnerAppId().equals(partnerId)) {
            throw new NotFoundException("Order");
        }
    }

    /**
     * IDOR: la orden debe pertenecer al usuario que paga/confirma. Si es de otro, 404 (no filtramos la
     * existencia de pedidos ajenos ni permitimos cargar el wallet del dueño desde otra cuenta). Se tolera
     * {@code order.userId == null} (pedido sin dueño explícito) por compatibilidad.
     */
    private void assertOrderOwnedBy(Order order, UUID userId) {
        if (order.getUserId() != null && userId != null && !order.getUserId().equals(userId)) {
            throw new NotFoundException("Order");
        }
    }

    /** IDOR: el pago debe ser del usuario autenticado (mismo criterio que {@code confirmMockRecharge}). */
    private void assertPaymentOwnedBy(Payment p, UUID userId) {
        if (p.getUserId() == null || userId == null || !p.getUserId().equals(userId)) {
            throw new NotFoundException(PAYMENT);
        }
    }

    /** ¿Estamos en un entorno productivo (pro/pre)? Ahí los pagos simulados están PROHIBIDOS. */
    private boolean isProdLikeProfile() {
        if (activeProfiles == null) {
            return false;
        }
        for (String prof : activeProfiles.split(",")) {
            String t = prof.trim().toLowerCase();
            if (t.equals("pro") || t.equals("pre") || t.equals("prod") || t.equals("production")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fail-closed: si la app corre en pro/pre, NUNCA se acepta una confirmación "mock" (que acredita el
     * wallet o marca el pedido pagado sin pasar por la pasarela real). Cierra el agujero de arrancar en
     * producción con la pasarela deshabilitada y obtener dinero/pedidos gratis.
     */
    private void assertMockAllowed() {
        if (isProdLikeProfile()) {
            throw new BusinessException("MOCK_PAYMENT_DISABLED",
                    "Los pagos simulados no están permitidos en este entorno");
        }
    }

    /** Pago de esa orden o 404. Sin anotar: lo usan confirmar y devolver, que ya abren su transacción. */
    private Payment requireOrderPayment(UUID orderId, UUID paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        if (p.getOrderId() != null && !p.getOrderId().equals(orderId)) {
            throw new NotFoundException(PAYMENT);
        }
        return p;
    }

    @Override
    @Transactional
    public Payment confirmMockOrderPayment(UUID userId, UUID orderId, UUID paymentId) {
        assertMockAllowed();
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() -> new NotFoundException(PAYMENT));
        // El pago debe corresponder a la orden indicada (evita confirmar un pago de otra orden)...
        if (p.getOrderId() == null || !p.getOrderId().equals(orderId)) {
            throw new NotFoundException(PAYMENT);
        }
        // ...y debe ser del usuario autenticado (IDOR: no confirmar/pagar el pedido de otro).
        assertPaymentOwnedBy(p, userId);
        // SEGURIDAD (idéntico a confirmMockRecharge): esta vía marca la orden como PAGADA SIN pasar por la
        // pasarela real. Solo se admite para pagos sintéticos de mock-mode (providerRef *_mock_). Un checkout
        // REAL de Stripe/PayPal (cs_test_/cs_live_/pi_...) no cobrado NUNCA se confirma aquí; de lo contrario
        // cualquiera crearía una orden con tarjeta y la marcaría PAGADA gratis (y se enviaría la mercancía).
        String ref = p.getProviderRef() != null ? p.getProviderRef() : "";
        boolean mock = ref.startsWith(CS_MOCK) || ref.startsWith(PAYPAL_MOCK) || ref.startsWith(PI_MOCK);
        if (!mock) {
            throw new BusinessException("PAYMENT_REQUIRES_REAL_CONFIRMATION",
                    "Este pago debe completarse en la pasarela de pago real");
        }
        return doConfirmSucceeded(p.getId(), Map.of(MOCK_CONFIRM, true, ORDERID, orderId.toString()));
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
            String paymentIdStr = meta != null ? String.valueOf(meta.get(PAYMENTID)) : null;
            if (paymentIdStr == null || "null".equals(paymentIdStr)) {
                Payment p = paymentRepository.findByProviderAndProviderRef("stripe", intentId).orElse(null);
                if (p != null)
                    paymentIdStr = p.getId().toString();
            }

            if ("customer.subscription.created".equals(eventType) || "customer.subscription.updated".equals(eventType)
                    || "customer.subscription.deleted".equals(eventType)) {
                String stripeSubId = String.valueOf(data.get("id"));
                String stripeStatus = String.valueOf(data.get(STATUS));
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

            // String.valueOf(null) devuelve la CADENA "null", no null: sin este segundo control un evento
            // sin metadata que además no casa con ningún pago se colaba hasta UUID.fromString("null") y
            // reventaba. Antes daba igual porque el fallo se tragaba; ahora haría que Stripe reintentase
            // tres días un evento que no va a casar nunca.
            if (paymentIdStr == null || "null".equals(paymentIdStr) || paymentIdStr.isBlank()) {
                return NO_MATCH;
            }
            UUID paymentId = UUID.fromString(paymentIdStr);
            if ("payment_intent.succeeded".equals(eventType)) {
                doConfirmSucceeded(paymentId, data);
            } else if ("payment_intent.payment_failed".equals(eventType)) {
                doMarkFailed(paymentId, "Stripe: payment_failed", data);
            }
        } catch (Exception e) {
            throw webhookFailure("stripe", eventType, e);
        }
        return "ok";
    }

    /**
     * Un fallo procesando el evento NO puede saldarse con un 200.
     *
     * <p>Devolver "ok" le decía a la pasarela que el evento quedó atendido, así que no lo reintentaba
     * nunca más: el cobro seguía hecho en su lado mientras aquí el pedido no pasaba a PAID o el saldo no
     * se acreditaba, y del incidente solo quedaba una línea de log que nadie mira. Se avisa al
     * responsable y se propaga para que el controlador responda 5xx y la pasarela reenvíe el evento
     * —Stripe reintenta durante tres días—.
     */
    private WebhookProcessingException webhookFailure(String provider, String eventType, Exception cause) {
        log.error("{} webhook processing failed for {}: {}", provider, eventType, cause.getMessage(), cause);
        opsAlertService.paymentFailed(provider, "webhook " + eventType, "-",
                cause.getMessage() != null ? cause.getMessage() : cause.toString());
        return new WebhookProcessingException(provider, eventType, cause);
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
                return NO_MATCH;
            if (eventType != null && eventType.contains("CAPTURE.COMPLETED")) {
                doConfirmSucceeded(p.getId(), resource);
            } else if (eventType != null && eventType.contains("DENIED")) {
                doMarkFailed(p.getId(), "PayPal: " + eventType, resource);
            }
        } catch (Exception e) {
            throw webhookFailure("paypal", "-", e);
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
                return NO_MATCH;
            if ("charge:confirmed".equals(type)) {
                doConfirmSucceeded(p.getId(), data);
            } else if ("charge:failed".equals(type) || "charge:delayed".equals(type)) {
                doMarkFailed(p.getId(), "Coinbase: " + type, data);
            }
        } catch (Exception e) {
            throw webhookFailure("coinbase", "-", e);
        }
        return "ok";
    }
}
