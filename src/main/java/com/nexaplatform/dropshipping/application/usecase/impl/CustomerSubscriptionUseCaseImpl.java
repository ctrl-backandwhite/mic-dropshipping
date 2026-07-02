package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Billing use-case implementation. Operates on the {@link CustomerSubscription}
 * domain model and delegates aggregate persistence to its domain port. The legacy
 * Spring Data {@code SubscriptionPlanRepository} is reused as a read-only
 * collaborator for plan-by-code/Stripe-price-id resolution and the feature-derived
 * public plan list; plan administration mutations are delegated to the migrated
 * {@link SubscriptionPlanUseCase}. Preserves the Stripe-disabled dev path and the
 * exact checkout behaviour moved out of the old {@code SubscriptionService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSubscriptionUseCaseImpl implements CustomerSubscriptionUseCase {

    private final CustomerSubscriptionRepository customerSubscriptionRepository;
    private final CustomerSubscriptionUpdateMapper customerSubscriptionUpdateMapper;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionPlanUseCase subscriptionPlanUseCase;
    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final CurrencyRateService currencyService;
    private final CountryTaxService countryTaxService;
    private final InvoiceService invoiceService;

    @Override
    @Transactional
    public CustomerSubscription save(CustomerSubscription model) {
        CustomerSubscription saved = customerSubscriptionRepository.save(model);
        log.info("::> [BILLING] Subscription created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> findAll() {
        return customerSubscriptionRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSubscription getById(UUID id) {
        CustomerSubscription model = customerSubscriptionRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Subscription not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    public CustomerSubscription update(CustomerSubscription model, UUID id) {
        CustomerSubscription existing = getById(id);
        customerSubscriptionUpdateMapper.updateFromModel(model, existing);
        CustomerSubscription saved = customerSubscriptionRepository.update(existing);
        log.info("::> [BILLING] Subscription updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        getById(id);
        customerSubscriptionRepository.delete(id);
        log.info("::> [BILLING] Subscription deleted id={}", id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> listAdminSubscriptions(String status) {
        return customerSubscriptionRepository.findAll().stream()
                // DROP-634: normaliza estado y periodo en la propia consulta para que
                // el LISTADO de admin sea consistente aunque existan datos legacy que
                // escapen a la migración (FREE+TRIALING, periodo MONTH/YEAR).
                .map(this::normalizeForAdmin)
                .filter(s -> status == null || status.isBlank()
                        || (s.getStatus() != null && s.getStatus().name().equalsIgnoreCase(status)))
                .toList();
    }

    /**
     * DROP-634: defensa en profundidad sobre la vista de admin.
     * <ul>
     *   <li>Un plan gratuito (FREE) nunca debe figurar en {@code TRIALING}: un plan
     *       sin coste está activo desde el inicio, así que lo presentamos {@code ACTIVE}.</li>
     *   <li>El periodo se canonicaliza a {@code MONTHLY}/{@code YEARLY} (formato del
     *       use case real) para que el frontend siempre lo traduzca vía i18n y nunca
     *       muestre el valor crudo del enum.</li>
     * </ul>
     * No persiste: sólo ajusta el modelo de lectura que viaja al DTO.
     */
    private CustomerSubscription normalizeForAdmin(CustomerSubscription s) {
        CustomerSubscription out = s;
        boolean isFree = "FREE".equalsIgnoreCase(out.getPlanCode())
                || (out.getPriceMonthly() == 0 && out.getPriceYearly() == 0);
        if (isFree && out.getStatus() == SubscriptionStatus.TRIALING) {
            out = out.withStatus(SubscriptionStatus.ACTIVE).withTrialEndsAt(null);
        }
        out = out.withBillingPeriod(canonicalPeriod(out.getBillingPeriod()));
        return out;
    }

    /** Maps the historical period conventions to the canonical MONTHLY/YEARLY values. */
    private String canonicalPeriod(String period) {
        if (period == null) {
            return null;
        }
        return switch (period.trim().toUpperCase()) {
            case "MONTH", "MONTHLY" -> "MONTHLY";
            case "YEAR", "YEARLY" -> "YEARLY";
            default -> period.toUpperCase();
        };
    }

    @Override
    @Transactional
    public CustomerSubscription createSubscription(UUID userId, String planCode, String billingPeriod) {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);
        Instant now = Instant.now();
        Instant end = "YEARLY".equalsIgnoreCase(billingPeriod)
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);
        CustomerSubscription model = CustomerSubscription.builder().userId(userId).planId(plan.getId())
                .status(SubscriptionStatus.ACTIVE)
                .billingPeriod(billingPeriod == null ? "MONTHLY" : billingPeriod.toUpperCase()).currentPeriodStart(now)
                .currentPeriodEnd(end).build();
        return customerSubscriptionRepository.save(model);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> listForUser(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public SubscribeResult subscribe(UUID userId, String planCode, String period) throws Exception {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);

        if (!stripeService.isEnabled()) {
            CustomerSubscription sub = createSubscription(userId, planCode, period);
            return SubscribeResult.builder().checkoutUrl("/billing/success?dev=1").sessionId(sub.getId().toString())
                    .build();
        }

        String priceId = "YEARLY".equalsIgnoreCase(period)
                ? plan.getStripeYearlyPriceId()
                : plan.getStripeMonthlyPriceId();
        var session = stripeService.createCheckoutSession("user@example.com", priceId,
                "http://localhost:3003/billing/success", "http://localhost:3003/billing/cancel");
        return SubscribeResult.builder().checkoutUrl(session.getUrl()).sessionId(session.getId()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlan> listAdminPlans() {
        return subscriptionPlanUseCase.findAll().stream().sorted(Comparator.comparingInt(SubscriptionPlan::getPosition))
                .toList();
    }

    @Override
    @Transactional
    public SubscriptionPlan updatePlan(String code, SubscriptionPlan changes) {
        SubscriptionPlanEntity plan = getPlanEntityByCode(code);
        // Overlay only the editable fields onto the current plan so the delegated
        // update (which copies every non-audit field) cannot blank out the
        // non-editable columns (code/currency/position), preserving the legacy
        // updatePlan contract.
        SubscriptionPlan current = subscriptionPlanUseCase.getById(plan.getId());
        SubscriptionPlan merged = current.withName(changes.getName() != null ? changes.getName() : current.getName())
                .withDescription(changes.getDescription() != null ? changes.getDescription() : current.getDescription())
                .withPriceMonthlyCents(changes.getPriceMonthlyCents() != 0
                        ? changes.getPriceMonthlyCents()
                        : current.getPriceMonthlyCents())
                .withPriceYearlyCents(changes.getPriceYearlyCents() != 0
                        ? changes.getPriceYearlyCents()
                        : current.getPriceYearlyCents())
                .withActive(changes.isActive());
        SubscriptionPlan updated = subscriptionPlanUseCase.update(merged, plan.getId());
        log.info("::> [BILLING] Plan updated code={}", code);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionPlanEntity> listPublicPlans() {
        // JOIN FETCH features: el mapper del controller los lee fuera de esta transacción (evita
        // LazyInitializationException que tiraba todo el listado de planes con 500).
        return planRepository.findActiveWithFeatures();
    }

    /** Resolves the managed plan entity by its unique code, failing if it does not exist. */
    private SubscriptionPlanEntity getPlanEntityByCode(String code) {
        return planRepository.findByCode(code).orElseThrow(() -> new NotFoundException("Plan not found: " + code));
    }

    // =============================================================================================
    // Métodos de pago en el perfil (tarjeta guardada vía Stripe Elements)
    // =============================================================================================

    @Override
    public BillingConfigInfo billingConfig() {
        return new BillingConfigInfo(stripeService.publishableKey(), stripeService.isEnabled());
    }

    @Override
    @Transactional
    public String createSetupIntentSecret(UUID userId) throws Exception {
        requireStripe();
        String customerId = resolveStripeCustomerId(userId);
        return stripeService.createSetupIntent(customerId).getClientSecret();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CardInfo> listCards(UUID userId) throws Exception {
        requireStripe();
        UserEntity user = loadUser(userId);
        String customerId = user.getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return List.of();
        }
        String defaultPm = stripeService.defaultPaymentMethodId(customerId);
        return stripeService.listCards(customerId).stream().map(pm -> toCardInfo(pm, defaultPm)).toList();
    }

    @Override
    @Transactional
    public void setDefaultCard(UUID userId, String paymentMethodId) throws Exception {
        requireStripe();
        String customerId = resolveStripeCustomerId(userId);
        assertCardBelongsToCustomer(customerId, paymentMethodId);
        stripeService.setDefaultPaymentMethod(customerId, paymentMethodId);
    }

    @Override
    @Transactional
    public void deleteCard(UUID userId, String paymentMethodId) throws Exception {
        requireStripe();
        String customerId = resolveStripeCustomerId(userId);
        assertCardBelongsToCustomer(customerId, paymentMethodId);
        stripeService.detachPaymentMethod(paymentMethodId);
    }

    private void requireStripe() {
        if (!stripeService.isEnabled()) {
            throw new BusinessException("Los pagos con tarjeta no están activos en este entorno.");
        }
    }

    private UserEntity loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    /** Devuelve el customerId de Stripe del usuario; lo crea y persiste de forma perezosa si no lo tiene. */
    private String resolveStripeCustomerId(UUID userId) throws Exception {
        UserEntity user = loadUser(userId);
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return user.getStripeCustomerId();
        }
        Customer customer = stripeService.getOrCreateCustomer(null, user.getEmail(), userId.toString());
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        log.info("::> [BILLING] Stripe customer creado user={} customer={}", userId, customer.getId());
        return customer.getId();
    }

    /** Evita que un usuario manipule (default/borrado) una tarjeta que no es de su Customer. */
    private void assertCardBelongsToCustomer(String customerId, String paymentMethodId) throws Exception {
        boolean owned = stripeService.listCards(customerId).stream()
                .anyMatch(pm -> pm.getId().equals(paymentMethodId));
        if (!owned) {
            throw new NotFoundException("Tarjeta no encontrada para el usuario");
        }
    }

    private CardInfo toCardInfo(PaymentMethod pm, String defaultPaymentMethodId) {
        PaymentMethod.Card card = pm.getCard();
        return new CardInfo(pm.getId(), card != null ? card.getBrand() : null, card != null ? card.getLast4() : null,
                card != null ? card.getExpMonth() : null, card != null ? card.getExpYear() : null,
                pm.getId().equals(defaultPaymentMethodId));
    }

    // =============================================================================================
    // Contratación de plan con la tarjeta guardada
    // =============================================================================================

    @Override
    @Transactional
    public SubscribeOutcome subscribeWithSavedCard(UUID userId, String planCode, String period) throws Exception {
        requireStripe();
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);
        String billingPeriod = "YEARLY".equalsIgnoreCase(period) ? "YEARLY" : "MONTHLY";
        int cnyCents = "YEARLY".equals(billingPeriod) ? plan.getPriceYearlyCents() : plan.getPriceMonthlyCents();

        // Plan gratis: suscripción ACTIVE directa, sin pasar por Stripe.
        if (cnyCents <= 0) {
            CustomerSubscription free = createSubscription(userId, planCode, billingPeriod);
            return new SubscribeOutcome(free.getId().toString(), "active");
        }

        // El país del perfil es obligatorio para contratar un plan de pago (se usa para el IVA de la factura).
        UserEntity user = loadUser(userId);
        if (user.getCountry() == null || user.getCountry().isBlank()) {
            throw new BusinessException("Selecciona un país en tu perfil antes de contratar un plan.");
        }

        String customerId = resolveStripeCustomerId(userId);
        String defaultPm = stripeService.defaultOrFirstCardId(customerId);
        if (defaultPm == null || defaultPm.isBlank()) {
            throw new BusinessException("Añade una tarjeta en tu perfil antes de contratar un plan.");
        }
        // Fija la tarjeta como predeterminada del customer (idempotente) para futuras renovaciones/UI.
        stripeService.setDefaultPaymentMethod(customerId, defaultPm);
        // IVA por país del usuario (misma config que productos: CountryTaxService.rateBpsFor) → TaxRate de
        // Stripe; la contratación incluye el IVA y aparece desglosado en la factura de Stripe.
        int taxBps = countryTaxService.rateBpsFor(user.getCountry());
        String taxRateId = stripeService.ensureTaxRate(user.getCountry(), taxBps);
        // Precio del plan en CNY (moneda de 1688) → USD canónico (igual que los productos).
        String src = plan.getCurrency() != null && !plan.getCurrency().isBlank() ? plan.getCurrency() : "CNY";
        BigDecimal cny = BigDecimal.valueOf(cnyCents).movePointLeft(2);
        BigDecimal usdAmount = currencyService.toUsd(cny, src);
        // Moneda e importe de COBRO del plan: MISMA regla que checkout/recarga y COINCIDE con el precio
        // MOSTRADO (BillingController redondea a entero en la divisa activa). EUR si la web está en EUR;
        // USD si está en USD; cualquier otra divisa, su equivalente en USD.
        String displayCode = CurrencyHolder.get();
        String chargeCurrency;
        long chargeCents;
        if ("EUR".equalsIgnoreCase(displayCode)) {
            chargeCurrency = "eur";
            chargeCents = currencyService.usdTo(usdAmount, "EUR").setScale(0, RoundingMode.HALF_UP)
                    .movePointRight(2).longValueExact();
        } else if (displayCode == null || displayCode.isBlank() || "USD".equalsIgnoreCase(displayCode)) {
            chargeCurrency = "usd";
            chargeCents = usdAmount.setScale(0, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } else {
            // Otra divisa: se muestra el precio en esa divisa (redondeado); se cobra su equivalente en USD.
            chargeCurrency = "usd";
            BigDecimal shown = currencyService.usdTo(usdAmount, displayCode).setScale(0, RoundingMode.HALF_UP);
            chargeCents = currencyService.toUsd(shown, displayCode).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        if (chargeCents <= 0) {
            chargeCents = 1; // Stripe exige importe > 0
        }
        String priceId = stripeService.ensureRecurringPrice(planCode, billingPeriod, chargeCents, chargeCurrency,
                plan.getName());

        // Fila local (INCOMPLETE) para pasar su id como metadata a Stripe; se actualiza con el resultado.
        CustomerSubscription local = customerSubscriptionRepository.save(CustomerSubscription.builder().userId(userId)
                .planId(plan.getId()).status(SubscriptionStatus.INCOMPLETE).billingPeriod(billingPeriod)
                .stripeCustomerId(customerId).build());

        StripeService.SubResult res = stripeService.createSubscription(customerId, priceId, defaultPm, taxRateId,
                planCode, userId.toString(), local.getId().toString());

        customerSubscriptionRepository.save(local.withStripeSubscriptionId(res.id())
                .withStatus(mapStripeStatus(res.status()))
                .withCurrentPeriodStart(res.periodStart() != null ? Instant.ofEpochSecond(res.periodStart()) : null)
                .withCurrentPeriodEnd(res.periodEnd() != null ? Instant.ofEpochSecond(res.periodEnd()) : null));
        log.info("::> [BILLING] Subscribed user={} plan={} status={}", userId, planCode, res.status());
        return new SubscribeOutcome(res.id(), res.status());
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSubscription currentSubscription(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId).stream()
                .filter(s -> s.getStatus() != SubscriptionStatus.CANCELED)
                .max(Comparator.comparing(CustomerSubscription::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    @Override
    @Transactional
    public void cancelMySubscription(UUID userId) throws Exception {
        CustomerSubscription sub = currentSubscription(userId);
        if (sub == null) {
            throw new NotFoundException("No tienes una suscripción activa");
        }
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
            StripeService.SubResult res = stripeService.cancelSubscription(sub.getStripeSubscriptionId(), true);
            customerSubscriptionRepository.save(sub.withStatus(mapStripeStatus(res.status()))
                    .withCancelAt(res.periodEnd() != null ? Instant.ofEpochSecond(res.periodEnd()) : Instant.now()));
        } else {
            customerSubscriptionRepository
                    .save(sub.withStatus(SubscriptionStatus.CANCELED).withCanceledAt(Instant.now()));
        }
        log.info("::> [BILLING] Subscription canceled user={}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> listInvoices(UUID userId) throws Exception {
        if (!stripeService.isEnabled()) {
            return List.of();
        }
        String customerId = loadUser(userId).getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            return List.of();
        }
        return stripeService.listInvoices(customerId, 24).stream()
                .map(i -> new InvoiceView(i.number(), i.total(), i.currency(), i.status(), i.created(), i.pdfUrl(),
                        i.hostedUrl()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderInvoicePdf(UUID userId, String number, String locale) throws Exception {
        requireStripe();
        String customerId = loadUser(userId).getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            throw new NotFoundException("Invoice");
        }
        // Propiedad: solo facturas del propio Customer del usuario (se busca por número entre las suyas).
        StripeService.InvoiceInfo inv = stripeService.listInvoices(customerId, 50).stream()
                .filter(i -> number.equals(i.number()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice"));
        boolean paid = "paid".equalsIgnoreCase(inv.status());
        InvoiceService.PlanInvoiceData data = new InvoiceService.PlanInvoiceData(inv.number(), inv.currency(),
                inv.subtotal(), inv.tax(), inv.total() != null ? inv.total() : 0L, inv.lineDescription(),
                inv.periodStart(), inv.periodEnd(), inv.created(), inv.customerName(), inv.customerEmail(), paid,
                inv.hostedUrl());
        return invoiceService.renderPlanInvoicePdf(data, locale);
    }

    @Override
    @Transactional
    public void syncFromStripe(String stripeSubscriptionId, String stripeStatus, Long currentPeriodEnd,
            Long cancelAtEpoch) {
        if (stripeSubscriptionId == null || stripeSubscriptionId.isBlank()) {
            return;
        }
        customerSubscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId).ifPresent(sub -> {
            CustomerSubscription u = sub.withStatus(mapStripeStatus(stripeStatus))
                    .withCancelAt(cancelAtEpoch != null ? Instant.ofEpochSecond(cancelAtEpoch) : null);
            if (currentPeriodEnd != null) {
                u = u.withCurrentPeriodEnd(Instant.ofEpochSecond(currentPeriodEnd));
            }
            if ("canceled".equals(stripeStatus)) {
                u = u.withCanceledAt(Instant.now());
            }
            customerSubscriptionRepository.save(u);
            log.info("::> [BILLING] Subscription synced from Stripe id={} status={}", stripeSubscriptionId,
                    stripeStatus);
        });
    }

    private SubscriptionStatus mapStripeStatus(String s) {
        if (s == null) {
            return SubscriptionStatus.INCOMPLETE;
        }
        return switch (s) {
            case "active" -> SubscriptionStatus.ACTIVE;
            case "trialing" -> SubscriptionStatus.TRIALING;
            case "past_due" -> SubscriptionStatus.PAST_DUE;
            case "canceled" -> SubscriptionStatus.CANCELED;
            case "paused" -> SubscriptionStatus.PAUSED;
            default -> SubscriptionStatus.INCOMPLETE;
        };
    }
}
