package com.nexaplatform.dropshipping.application.usecase.impl;

import com.stripe.exception.StripeException;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.SubscriptionPlanUseCase;
import com.nexaplatform.dropshipping.domain.enums.InvoiceLabel;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionPlanLabel;
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
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionUpdateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String MONTHLY = "MONTHLY";
    private static final String YEARLY = "YEARLY";
    /** Duración de la prueba GRATIS: 15 días, un solo uso por cuenta. */
    private static final int FREE_TRIAL_DAYS = 15;

    private final CustomerSubscriptionRepository customerSubscriptionRepository;
    private final CustomerSubscriptionUpdateMapper customerSubscriptionUpdateMapper;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionPlanUseCase subscriptionPlanUseCase;
    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final CurrencyRateService currencyService;
    private final CountryTaxService countryTaxService;
    private final InvoiceService invoiceService;
    private final SubscriptionNotificationService subscriptionNotificationService;

    /** URL pública del escaparate, para las vueltas de Stripe. La misma que usan los correos. */
    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String storefrontBaseUrl;

    /*
     * Rollback de las operaciones que hablan con Stripe: declaran noRollbackFor = StripeException.class.
     * Coincide con lo que Spring hace por defecto ante una excepción COMPROBADA, pero aquí es una
     * decisión y no un descuido: lo que se escribe antes de llamar a Stripe (el customerId del usuario,
     * la fila INCOMPLETE de la suscripción) es el espejo local de algo que YA existe en su plataforma.
     * Deshacerlo dejaría los dos lados desincronizados y el siguiente intento crearía un Customer o una
     * suscripción duplicados en Stripe, que es un daño peor que quedarse con una fila a medias.
     */

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
        return requireById(id);
    }

    /**
     * Cuerpo sin anotar de {@link #getById(UUID)}, que es al que llaman los métodos de esta clase: una
     * llamada interna no pasa por el proxy de Spring, así que su {@code @Transactional} nunca se aplicaría.
     * La transacción la abre el método público de entrada.
     */
    private CustomerSubscription requireById(UUID id) {
        CustomerSubscription model = customerSubscriptionRepository.getById(id);
        if (Objects.isNull(model)) {
            throw new NotFoundException("Subscription not found: " + id);
        }
        return model;
    }

    @Override
    @Transactional
    public CustomerSubscription update(CustomerSubscription model, UUID id) {
        CustomerSubscription existing = requireById(id);
        customerSubscriptionUpdateMapper.updateFromModel(model, existing);
        CustomerSubscription saved = customerSubscriptionRepository.update(existing);
        log.info("::> [BILLING] Subscription updated id={}", id);
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        requireById(id);
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
            case "MONTH", MONTHLY -> MONTHLY;
            case "YEAR", YEARLY -> YEARLY;
            default -> period.toUpperCase();
        };
    }

    @Override
    @Transactional
    public CustomerSubscription createSubscription(UUID userId, String planCode, String billingPeriod) {
        return newSubscription(userId, planCode, billingPeriod);
    }

    /** Cuerpo sin anotar de {@link #createSubscription}: es al que llaman los flujos de esta clase. */
    private CustomerSubscription newSubscription(UUID userId, String planCode, String billingPeriod) {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);
        Instant now = Instant.now();

        // Plan GRATIS = PRUEBA de 15 días, un solo uso por cuenta/correo (rechaza el 2º intento).
        if (isFreePlan(plan)) {
            return startFreeTrial(userId, plan, now);
        }

        Instant end = YEARLY.equalsIgnoreCase(billingPeriod)
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);
        CustomerSubscription model = CustomerSubscription.builder().userId(userId).planId(plan.getId())
                .status(SubscriptionStatus.ACTIVE)
                .billingPeriod(billingPeriod == null ? MONTHLY : billingPeriod.toUpperCase()).currentPeriodStart(now)
                .currentPeriodEnd(end).build();
        CustomerSubscription savedPaid = customerSubscriptionRepository.save(model);
        subscriptionNotificationService.planActivated(userId, plan.getCode(), end, false);
        return savedPaid;
    }

    /**
     * Contrata el plan de PRUEBA (gratis): vence en 15 días y solo puede usarse UNA vez por cuenta/correo. La
     * fila queda {@code ACTIVE} con {@code currentPeriodEnd = ahora + 1 mes} (el modelo trata FREE como
     * activo, no como TRIALING — misma convención que {@link #normalizeForAdmin} y schema-v33); el
     * vencimiento lo aplica el barrido {@link #expireFreeTrials()}. Marca {@code freeTrialUsed=true} en el
     * usuario para impedir una segunda prueba gratis desde la misma cuenta.
     */
    private CustomerSubscription startFreeTrial(UUID userId, SubscriptionPlanEntity plan, Instant now) {
        UserEntity user = loadUser(userId);
        // Un solo uso por cuenta: la marca es la fuente de verdad, pero además bloqueamos si ya EXISTE una
        // suscripción FREE (cuentas antiguas cuya marca no se fijó) — defensa en profundidad.
        boolean hadFree = customerSubscriptionRepository.findByUserId(userId).stream()
                .anyMatch(s -> "FREE".equalsIgnoreCase(s.getPlanCode()));
        if (user.isFreeTrialUsed() || hadFree) {
            throw new BusinessException("FREE_TRIAL_ALREADY_USED",
                    "Ya has utilizado tu prueba gratis de 15 días. Elige un plan de pago.");
        }
        Instant trialEnd = now.plus(FREE_TRIAL_DAYS, ChronoUnit.DAYS);
        CustomerSubscription model = CustomerSubscription.builder().userId(userId).planId(plan.getId())
                .status(SubscriptionStatus.ACTIVE).billingPeriod(MONTHLY).currentPeriodStart(now)
                .currentPeriodEnd(trialEnd).build();
        CustomerSubscription saved = customerSubscriptionRepository.save(model);
        user.setFreeTrialUsed(true);
        userRepository.save(user);
        subscriptionNotificationService.planActivated(userId, plan.getCode(), trialEnd, true);
        log.info("::> [BILLING] Free trial started user={} plan={} endsAt={}", userId, plan.getCode(), trialEnd);
        return saved;
    }

    /** Un plan es "gratis/prueba" si su código es FREE o si no tiene coste ni mensual ni anual. */
    private boolean isFreePlan(SubscriptionPlanEntity plan) {
        return "FREE".equalsIgnoreCase(plan.getCode())
                || (plan.getPriceMonthlyCents() <= 0 && plan.getPriceYearlyCents() <= 0);
    }

    /**
     * Vencimiento del plan de PRUEBA (gratis). Cada hora marca como {@code CANCELED} las suscripciones FREE
     * cuyo mes ya expiró ({@code currentPeriodEnd} en el pasado). El modelo no tiene estado EXPIRED, así que
     * usamos CANCELED con {@code canceledAt}: deja al usuario SIN plan activo ({@link #currentSubscription}
     * ignora CANCELED), de modo que debe contratar un plan de pago. Los planes de PAGO NO se tocan (su ciclo
     * lo gobierna Stripe): se excluyen por precio y por llevar {@code stripeSubscriptionId}.
     */
    @Scheduled(fixedDelay = 3_600_000L)
    @Transactional
    public void expireFreeTrials() {
        Instant now = Instant.now();
        for (CustomerSubscription sub : customerSubscriptionRepository.findAll()) {
            // El plan tiene que estar IDENTIFICADO para decidir. planCode y los precios los computa el
            // mapper desde la relación `plan`: si no está cargada valen null y 0, y sin este control una
            // suscripción DE PAGO con la relación suelta se tomaba por gratuita y se cancelaba sola,
            // cortándole el servicio a alguien que está pagando. Ante la duda, no se toca.
            boolean planKnown = sub.getPlanCode() != null && !sub.getPlanCode().isBlank();
            boolean free = planKnown && ("FREE".equalsIgnoreCase(sub.getPlanCode())
                    || (sub.getPriceMonthly() <= 0 && sub.getPriceYearly() <= 0));
            boolean active = sub.getStatus() == SubscriptionStatus.ACTIVE
                    || sub.getStatus() == SubscriptionStatus.TRIALING;
            boolean expired = sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isBefore(now);
            boolean stripeManaged = sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank();
            if (free && active && expired && !stripeManaged) {
                customerSubscriptionRepository.save(sub.withStatus(SubscriptionStatus.CANCELED).withCanceledAt(now));
                log.info("::> [BILLING] Free trial expired → canceled subId={} user={}", sub.getId(),
                        sub.getUserId());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerSubscription> listForUser(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId);
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public SubscribeResult subscribe(UUID userId, String planCode, String period) throws StripeException {
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);

        if (!stripeService.isEnabled()) {
            CustomerSubscription sub = newSubscription(userId, planCode, period);
            return SubscribeResult.builder().checkoutUrl("/billing/success?dev=1").sessionId(sub.getId().toString())
                    .build();
        }

        String priceId = YEARLY.equalsIgnoreCase(period)
                ? plan.getStripeYearlyPriceId()
                : plan.getStripeMonthlyPriceId();
        // El correo y las URL de retorno estaban CABLEADOS ("user@example.com" y localhost): en
        // producción el recibo de Stripe se enviaba a una dirección falsa y, al terminar de pagar, el
        // cliente acababa redirigido a una máquina que no existe. Se toman del usuario y de la URL
        // configurada del escaparate, la misma que usan los correos.
        String email = loadUser(userId).getEmail();
        Session session = stripeService.createCheckoutSession(email, priceId,
                storefrontBaseUrl + "/billing/success", storefrontBaseUrl + "/billing/cancel");
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
    @Transactional(readOnly = true)
    public BillingConfigInfo billingConfig(UUID userId) {
        boolean flag = userId != null && userRepository.findById(userId)
                .map(UserEntity::isFreeTrialUsed).orElse(false);
        boolean hadFree = userId != null && customerSubscriptionRepository.findByUserId(userId).stream()
                .anyMatch(s -> "FREE".equalsIgnoreCase(s.getPlanCode()));
        return new BillingConfigInfo(stripeService.publishableKey(), stripeService.isEnabled(), flag || hadFree);
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public String createSetupIntentSecret(UUID userId) throws StripeException {
        requireStripe();
        String customerId = resolveStripeCustomerId(userId);
        return stripeService.createSetupIntent(customerId).getClientSecret();
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public List<CardInfo> listCards(UUID userId) throws StripeException {
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
    @Transactional(noRollbackFor = StripeException.class)
    public void setDefaultCard(UUID userId, String paymentMethodId) throws StripeException {
        requireStripe();
        String customerId = resolveStripeCustomerId(userId);
        assertCardBelongsToCustomer(customerId, paymentMethodId);
        stripeService.setDefaultPaymentMethod(customerId, paymentMethodId);
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public void deleteCard(UUID userId, String paymentMethodId) throws StripeException {
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
    private String resolveStripeCustomerId(UUID userId) throws StripeException {
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
    private void assertCardBelongsToCustomer(String customerId, String paymentMethodId) throws StripeException {
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
    @Transactional(noRollbackFor = StripeException.class)
    public SubscribeOutcome subscribeWithSavedCard(UUID userId, String planCode, String period) throws StripeException {
        requireStripe();
        SubscriptionPlanEntity plan = getPlanEntityByCode(planCode);
        String billingPeriod = YEARLY.equalsIgnoreCase(period) ? YEARLY : MONTHLY;
        int cnyCents = YEARLY.equals(billingPeriod) ? plan.getPriceYearlyCents() : plan.getPriceMonthlyCents();

        // Plan gratis: suscripción ACTIVE directa, sin pasar por Stripe.
        if (cnyCents <= 0) {
            CustomerSubscription free = newSubscription(userId, planCode, billingPeriod);
            return new SubscribeOutcome(free.getId().toString(), "active");
        }

        // El país del perfil es obligatorio para contratar un plan de pago (se usa para el IVA de la factura).
        UserEntity user = loadUser(userId);
        if (user.getCountry() == null || user.getCountry().isBlank()) {
            throw new BusinessException("PLAN_COUNTRY_REQUIRED",
                    "Selecciona un país en tu perfil antes de contratar un plan.");
        }

        String customerId = resolveStripeCustomerId(userId);
        String defaultPm = stripeService.defaultOrFirstCardId(customerId);
        if (defaultPm == null || defaultPm.isBlank()) {
            throw new BusinessException("PLAN_CARD_REQUIRED",
                    "Añade una tarjeta en tu perfil antes de contratar un plan.");
        }
        // Fija la tarjeta como predeterminada del customer (idempotente) para futuras renovaciones/UI.
        stripeService.setDefaultPaymentMethod(customerId, defaultPm);
        // IVA por país del usuario (misma config que productos: CountryTaxService.rateBpsFor) → TaxRate de
        // Stripe; la contratación incluye el IVA y aparece desglosado en la factura de Stripe.
        int taxBps = countryTaxService.rateBpsFor(user.getCountry());
        String taxRateId = stripeService.ensureTaxRate(user.getCountry(), taxBps);
        // Precio del plan: base en USD (ancla del "resto del mundo") → USD canónico. Para la UE se cobra el
        // ancla FIJA en EUR (no una conversión del USD).
        String src = plan.getCurrency() != null && !plan.getCurrency().isBlank() ? plan.getCurrency() : "CNY";
        BigDecimal cny = BigDecimal.valueOf(cnyCents).movePointLeft(2);
        BigDecimal usdAmount = currencyService.toUsd(cny, src);
        int eurAnchorCents = YEARLY.equals(billingPeriod) ? plan.getPriceYearlyEurCents() : plan.getPriceMonthlyEurCents();
        StripeCharge charge = chargeFor(usdAmount, eurAnchorCents);
        String priceId = stripeService.ensureRecurringPrice(planCode, billingPeriod, charge.cents(),
                charge.currency(), plan.getName());

        // CAMBIO de plan sobre una suscripción de pago YA existente (no crear una nueva):
        //  · SUBIDA  → se cobra AHORA solo la diferencia por los días que quedan (prorrateo, ALWAYS_INVOICE).
        //  · BAJADA  → se mantiene el plan actual con TODAS sus características hasta fin de mes y en la
        //              renovación se cobra y aplica el plan menor (NONE + cambio pendiente que aplica el barrido).
        CustomerSubscription existing = latestActivePaidStripeSub(userId);
        if (existing != null) {
            return changePlan(userId, existing, plan, billingPeriod, priceId);
        }
        // Sin plan de pago activo: se contrata uno nuevo. Si había una PRUEBA gratis en curso, se sustituye.
        supersedeFreeTrial(userId);

        // Fila local (INCOMPLETE) para pasar su id como metadata a Stripe; se actualiza con el resultado.
        CustomerSubscription local = customerSubscriptionRepository.save(CustomerSubscription.builder().userId(userId)
                .planId(plan.getId()).status(SubscriptionStatus.INCOMPLETE).billingPeriod(billingPeriod)
                .stripeCustomerId(customerId).build());

        StripeService.SubResult res = stripeService.createSubscription(customerId, priceId, defaultPm, taxRateId,
                planCode, userId.toString(), local.getId().toString());

        Instant paidEnd = res.periodEnd() != null ? Instant.ofEpochSecond(res.periodEnd()) : null;
        customerSubscriptionRepository.save(local.withStripeSubscriptionId(res.id())
                .withStatus(mapStripeStatus(res.status()))
                .withCurrentPeriodStart(res.periodStart() != null ? Instant.ofEpochSecond(res.periodStart()) : null)
                .withCurrentPeriodEnd(paidEnd));
        // Adjunta la FACTURA (PDF, en el idioma del usuario) al correo de confirmación. Best-effort: si la
        // factura aún no está lista (p. ej. 3DS pendiente) o falla el render, se envía el correo sin adjunto.
        byte[] invoicePdf = null;
        String invoiceFilename = null;
        try {
            String lang = loadUser(userId).getLanguage();
            List<StripeService.InvoiceInfo> invs = stripeService.listInvoices(customerId, 1);
            if (!invs.isEmpty() && invs.get(0).number() != null) {
                invoiceFilename = "factura-" + invs.get(0).number() + ".pdf";
                invoicePdf = renderInvoicePdf(userId, invs.get(0).number(), lang);
            }
        } catch (Exception e) {
            log.warn("::> [BILLING] no se pudo generar la factura para adjuntar user={}: {}", userId, e.getMessage());
        }
        subscriptionNotificationService.planActivated(userId, plan.getCode(), paidEnd, false, invoicePdf,
                invoiceFilename);
        log.info("::> [BILLING] Subscribed user={} plan={} status={}", userId, planCode, res.status());
        return new SubscribeOutcome(res.id(), res.status());
    }

    /** Suscripción de PAGO activa (con id de Stripe, no cancelándose) más reciente del usuario, o null. */
    private CustomerSubscription latestActivePaidStripeSub(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId).stream()
                .filter(s -> s.getStripeSubscriptionId() != null && !s.getStripeSubscriptionId().isBlank())
                .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE
                        || s.getStatus() == SubscriptionStatus.TRIALING
                        || s.getStatus() == SubscriptionStatus.PAST_DUE)
                .filter(s -> s.getCancelAt() == null)
                .max(java.util.Comparator.comparing(
                        s -> s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart() : Instant.EPOCH))
                .orElse(null);
    }

    /** Cancela una PRUEBA gratis local en curso (sin Stripe) al contratar un plan de pago, para no duplicar. */
    private void supersedeFreeTrial(UUID userId) {
        customerSubscriptionRepository.findByUserId(userId).stream()
                .filter(s -> (s.getStripeSubscriptionId() == null || s.getStripeSubscriptionId().isBlank())
                        && s.getStatus() == SubscriptionStatus.ACTIVE
                        && "FREE".equalsIgnoreCase(s.getPlanCode()))
                .forEach(s -> customerSubscriptionRepository.save(
                        s.withStatus(SubscriptionStatus.CANCELED).withCanceledAt(Instant.now())));
    }

    /**
     * Cambia de plan sobre una suscripción de pago EXISTENTE. Subida → cobra ahora el prorrateo (diferencia
     * por los días restantes). Bajada → se aplica en la próxima renovación manteniendo el plan actual hasta
     * entonces (cambio pendiente que aplica {@link #applyPendingDowngrades()}).
     */
    private SubscribeOutcome changePlan(UUID userId, CustomerSubscription existing, SubscriptionPlanEntity newPlan,
            String newPeriod, String newPriceId) throws StripeException {
        SubscriptionPlanEntity currentPlan = getPlanEntityByCode(existing.getPlanCode());
        int curTier = currentPlan != null ? currentPlan.getPriceMonthlyCents() : 0;
        int newTier = newPlan.getPriceMonthlyCents();
        boolean samePlan = currentPlan != null && currentPlan.getId().equals(newPlan.getId())
                && newPeriod.equalsIgnoreCase(existing.getBillingPeriod());
        if (samePlan) {
            return new SubscribeOutcome(existing.getStripeSubscriptionId(), "active");
        }
        boolean upgrade = newTier > curTier
                || (newTier == curTier && YEARLY.equalsIgnoreCase(newPeriod)
                        && !YEARLY.equalsIgnoreCase(existing.getBillingPeriod()));
        String subId = existing.getStripeSubscriptionId();

        if (upgrade) {
            // Cobra AHORA la diferencia prorrateada por los días que quedan del periodo.
            StripeService.SubResult res = stripeService.changeSubscriptionPrice(subId, newPriceId, newPlan.getCode(),
                    SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE);
            Instant end = res.periodEnd() != null ? Instant.ofEpochSecond(res.periodEnd())
                    : existing.getCurrentPeriodEnd();
            customerSubscriptionRepository.save(existing.withPlanId(newPlan.getId())
                    .withBillingPeriod(newPeriod.toUpperCase()).withStatus(mapStripeStatus(res.status()))
                    .withCurrentPeriodStart(res.periodStart() != null ? Instant.ofEpochSecond(res.periodStart())
                            : existing.getCurrentPeriodStart())
                    .withCurrentPeriodEnd(end).withPendingPlanCode(null).withPendingPlanAt(null));
            attachInvoiceAndNotify(userId, existing.getStripeCustomerId(), newPlan.getCode(), end);
            log.info("::> [BILLING] Plan UPGRADE user={} -> {} (prorrateo cobrado)", userId, newPlan.getCode());
            return new SubscribeOutcome(subId, "active");
        }

        // BAJADA: el precio nuevo (menor) se aplicará en la renovación; el periodo actual sigue al plan actual.
        stripeService.changeSubscriptionPrice(subId, newPriceId, newPlan.getCode(),
                SubscriptionUpdateParams.ProrationBehavior.NONE);
        Instant at = existing.getCurrentPeriodEnd();
        customerSubscriptionRepository.save(existing.withPendingPlanCode(newPlan.getCode()).withPendingPlanAt(at));
        log.info("::> [BILLING] Plan DOWNGRADE programado user={} -> {} el {}", userId, newPlan.getCode(), at);
        return new SubscribeOutcome(subId, "scheduled");
    }

    /** Renderiza la última factura del usuario (idioma de la cuenta) y encola el correo de plan con ella. */
    private void attachInvoiceAndNotify(UUID userId, String customerId, String planCode, Instant periodEnd) {
        byte[] pdf = null;
        String filename = null;
        try {
            String lang = loadUser(userId).getLanguage();
            List<StripeService.InvoiceInfo> invs = stripeService.listInvoices(customerId, 1);
            if (!invs.isEmpty() && invs.get(0).number() != null) {
                filename = "factura-" + invs.get(0).number() + ".pdf";
                pdf = renderInvoicePdf(userId, invs.get(0).number(), lang);
            }
        } catch (Exception e) {
            log.warn("::> [BILLING] no se pudo adjuntar la factura del cambio de plan user={}: {}", userId,
                    e.getMessage());
        }
        subscriptionNotificationService.planActivated(userId, planCode, periodEnd, false, pdf, filename);
    }

    /**
     * Aplica las BAJADAS de plan programadas cuya fecha (fin de periodo) ya llegó: pone el plan menor como
     * plan efectivo (Stripe ya cambió el precio en la renovación). Cada hora.
     */
    @Scheduled(fixedDelay = 3_600_000L)
    @Transactional
    public void applyPendingDowngrades() {
        Instant now = Instant.now();
        for (CustomerSubscription sub : customerSubscriptionRepository.findAll()) {
            if (sub.getPendingPlanCode() == null || sub.getPendingPlanAt() == null
                    || sub.getPendingPlanAt().isAfter(now)) {
                continue;
            }
            // Defensa: si la suscripción se está cancelando o ya está cancelada, la bajada no aplica.
            if (sub.getCancelAt() != null || sub.getStatus() == SubscriptionStatus.CANCELED) {
                customerSubscriptionRepository.save(sub.withPendingPlanCode(null).withPendingPlanAt(null));
                continue;
            }
            planRepository.findByCode(sub.getPendingPlanCode()).ifPresent(plan -> {
                customerSubscriptionRepository.save(sub.withPlanId(plan.getId())
                        .withBillingPeriod(sub.getBillingPeriod()).withPendingPlanCode(null).withPendingPlanAt(null));
                log.info("::> [BILLING] Bajada de plan aplicada subId={} -> {}", sub.getId(), plan.getCode());
            });
        }
    }

    /** Moneda e importe (en la subunidad de esa moneda) con los que se cobra el plan en Stripe. */
    private record StripeCharge(String currency, long cents) {
    }

    /**
     * Moneda e importe de COBRO del plan a partir de su precio en USD canónico: MISMA regla que el
     * checkout y la recarga, y COINCIDE con el precio MOSTRADO —BillingController lo redondea a entero en
     * la divisa activa—. La divisa activa la fija la detección por IP (geo: EUR para la UE, USD para el
     * resto). En EUR se cobra el ANCLA fija en EUR ({@code eurAnchorCents}, p. ej. 50 €), no la conversión
     * del USD; en USD se cobra el ancla USD; con cualquier otra divisa se muestra en ella pero se cobra su
     * equivalente en USD.
     */
    private StripeCharge chargeFor(BigDecimal usdAmount, int eurAnchorCents) {
        String displayCode = CurrencyHolder.get();
        String chargeCurrency;
        long chargeCents;
        if ("EUR".equalsIgnoreCase(displayCode)) {
            chargeCurrency = "eur";
            chargeCents = eurAnchorCents > 0
                    ? eurAnchorCents
                    : currencyService.usdTo(usdAmount, "EUR").setScale(0, RoundingMode.HALF_UP)
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
        return new StripeCharge(chargeCurrency, chargeCents);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerSubscription currentSubscription(UUID userId) {
        return latestNonCanceled(userId);
    }

    /** Cuerpo sin anotar de {@link #currentSubscription(UUID)}: es al que llaman los flujos internos. */
    private CustomerSubscription latestNonCanceled(UUID userId) {
        return customerSubscriptionRepository.findByUserId(userId).stream()
                .filter(s -> s.getStatus() != SubscriptionStatus.CANCELED)
                .max(Comparator.comparing(CustomerSubscription::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    @Override
    @Transactional(noRollbackFor = StripeException.class)
    public void cancelMySubscription(UUID userId) throws StripeException {
        CustomerSubscription sub = latestNonCanceled(userId);
        if (sub == null) {
            throw new NotFoundException("No tienes una suscripción activa");
        }
        // La cancelación PREVALECE sobre una bajada de plan programada: si se cancela toda la suscripción,
        // ya no hay plan al que bajar. Se limpia el cambio pendiente para no mostrar "se cancela" + "bajarás
        // a X" a la vez.
        if (sub.getStripeSubscriptionId() != null && !sub.getStripeSubscriptionId().isBlank()) {
            StripeService.SubResult res = stripeService.cancelSubscription(sub.getStripeSubscriptionId(), true);
            customerSubscriptionRepository.save(sub.withStatus(mapStripeStatus(res.status()))
                    .withCancelAt(res.periodEnd() != null ? Instant.ofEpochSecond(res.periodEnd()) : Instant.now())
                    .withPendingPlanCode(null).withPendingPlanAt(null));
        } else {
            customerSubscriptionRepository.save(sub.withStatus(SubscriptionStatus.CANCELED)
                    .withCanceledAt(Instant.now()).withPendingPlanCode(null).withPendingPlanAt(null));
        }
        log.info("::> [BILLING] Subscription canceled user={}", userId);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public List<InvoiceView> listInvoices(UUID userId) throws StripeException {
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
    @Transactional(readOnly = true, noRollbackFor = StripeException.class)
    public byte[] renderInvoicePdf(UUID userId, String number, String locale) throws StripeException {
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
        String line = localizedPlanLine(userId, inv, locale);
        InvoiceService.PlanInvoiceData data = new InvoiceService.PlanInvoiceData(inv.number(), inv.currency(),
                inv.subtotal(), inv.tax(), inv.total() != null ? inv.total() : 0L, line,
                inv.periodStart(), inv.periodEnd(), inv.created(), inv.customerName(), inv.customerEmail(), paid,
                inv.hostedUrl());
        return invoiceService.renderPlanInvoicePdf(data, locale);
    }

    /**
     * Concepto de la factura del plan EN EL IDIOMA del usuario (Stripe lo genera en inglés: "Starter —
     * MONTHLY (at €50.00 / month)"). Resuelve el plan+periodo de la suscripción del usuario (la que casa por
     * inicio de periodo con la factura, o la más reciente) → "Inicial — Mensual". Si no se puede resolver,
     * cae al texto de Stripe para no dejar la línea vacía.
     */
    private String localizedPlanLine(UUID userId, StripeService.InvoiceInfo inv, String locale) {
        String lang = InvoiceLabel.lang(locale);
        List<CustomerSubscription> subs = customerSubscriptionRepository.findByUserId(userId);
        CustomerSubscription match = subs.stream()
                .filter(s -> s.getCurrentPeriodStart() != null && inv.periodStart() != null
                        && Math.abs(s.getCurrentPeriodStart().getEpochSecond() - inv.periodStart()) < 172800)
                .findFirst()
                .orElse(subs.stream()
                        .max(java.util.Comparator.comparing(
                                s -> s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart() : Instant.EPOCH))
                        .orElse(null));
        if (match == null || match.getPlanCode() == null) {
            return inv.lineDescription();
        }
        String name = SubscriptionPlanLabel.planName(match.getPlanCode(), lang);
        String period = SubscriptionPlanLabel.period(match.getBillingPeriod(), lang);
        return period.isBlank() ? name : name + " — " + period;
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
