package com.nexaplatform.dropshipping.infrastructure.integration.stripe;

import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Price;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.model.TaxRate;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.InvoiceListParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.PriceListParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.TaxRateCreateParams;
import com.stripe.param.TaxRateListParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cliente de Stripe para SUSCRIPCIONES de planes. Cubre el flujo "tarjeta guardada en el perfil":
 * crear/obtener Customer, guardar tarjeta vía SetupIntent (Elements), listar/borrar/poner-default los
 * métodos de pago, y crear/cambiar/cancelar la suscripción cobrando con la tarjeta por defecto.
 *
 * <p><b>Marca de origen</b>: cada suscripción (y por herencia sus facturas y cargos) lleva metadata
 * {@code platform}, {@code env} y, sobre todo, {@code purpose=subscription} + {@code plan_code} +
 * {@code user_id}. Así en el dashboard de Stripe se distingue el ingreso por PLANES del de productos
 * (que marca {@code orderId}); se filtra por {@code metadata['purpose']='subscription'}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeService {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String SUBSCRIPTION = "subscription";
    private static final String PLAN_CODE = "plan_code";
    private static final String PLATFORM = "platform";
    private static final String PURPOSE = "purpose";

    @Value("${nexadrop.stripe.enabled:false}")
    private boolean enabled;

    @Value("${nexadrop.stripe.secret-key:}")
    private String secretKey;

    /** Clave pública para Stripe Elements en el frontend (se expone vía endpoint público de billing). */
    @Value("${nexadrop.stripe.publishable-key:}")
    private String publishableKey;

    /** Secreto del webhook endpoint de Stripe (verifica la firma de los eventos entrantes). */
    @Value("${nexadrop.stripe.webhook-secret:}")
    private String webhookSecret;

    /**
     * Identificador de plataforma que se adjunta como metadata a cada pago/suscripción. Permite filtrar
     * en el dashboard de Stripe los cobros originados en la plataforma de Dropshipping cuando varias
     * plataformas comparten la misma cuenta Stripe.
     */
    @Value("${nexadrop.stripe.platform-id:nexadrop-dropshipping}")
    private String platformId;

    @Value("${nexadrop.stripe.platform-env:dev}")
    private String platformEnv;

    /**
     * {@code Stripe.apiKey} es un campo ESTÁTICO GLOBAL del SDK de Stripe: no hay forma de configurar la
     * clave por instancia. Se escribe desde aquí porque este es el único punto donde ya está resuelta la
     * configuración del entorno (@Value) y antes de que se atienda ninguna petición. De ahí la excepción
     * a java:S2696 ("no escribir campos estáticos desde un método de instancia").
     */
    @PostConstruct
    @SuppressWarnings("java:S2696")
    public void init() {
        if (enabled && secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe enabled (platform={}, env={})", platformId, platformEnv);
        } else {
            log.info("Stripe disabled (test mode); checkout will return mock URLs");
        }
    }

    public boolean isEnabled() {
        return enabled && secretKey != null && !secretKey.isBlank();
    }

    /** Clave pública para el frontend (Stripe Elements). Vacía si Stripe no está configurado. */
    public String publishableKey() {
        return publishableKey == null ? "" : publishableKey;
    }

    // =================================================================================================
    // Checkout alojado (flujo legacy, se mantiene por compatibilidad del botón actual de /plans)
    // =================================================================================================
    public Session createCheckoutSession(String customerEmail, String stripePriceId, String successUrl,
            String cancelUrl) throws StripeException {
        SessionCreateParams params = SessionCreateParams.builder().setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}").setCancelUrl(cancelUrl)
                .setCustomerEmail(customerEmail)
                .addLineItem(SessionCreateParams.LineItem.builder().setPrice(stripePriceId).setQuantity(1L).build())
                .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                .putMetadata(PURPOSE, SUBSCRIPTION)
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                        .putMetadata(PURPOSE, SUBSCRIPTION).build())
                .build();
        return Session.create(params);
    }

    // =================================================================================================
    // Customer + métodos de pago (tarjeta guardada en el perfil)
    // =================================================================================================

    /** Devuelve el Customer existente o crea uno nuevo (con metadata de plataforma + user_id). */
    public Customer getOrCreateCustomer(String existingCustomerId, String email, String userId)
            throws StripeException {
        if (existingCustomerId != null && !existingCustomerId.isBlank()) {
            return Customer.retrieve(existingCustomerId);
        }
        CustomerCreateParams params = CustomerCreateParams.builder().setEmail(email)
                .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                .putMetadata("user_id", userId).build();
        return Customer.create(params);
    }

    /** Crea un SetupIntent para guardar una tarjeta con Stripe Elements; devuelve su client_secret. */
    public SetupIntent createSetupIntent(String customerId) throws StripeException {
        SetupIntentCreateParams params = SetupIntentCreateParams.builder().setCustomer(customerId)
                .addPaymentMethodType("card").putMetadata(PLATFORM, platformId)
                .putMetadata("env", platformEnv).build();
        return SetupIntent.create(params);
    }

    /** Lista las tarjetas guardadas del Customer. */
    public List<PaymentMethod> listCards(String customerId) throws StripeException {
        return PaymentMethod.list(PaymentMethodListParams.builder().setCustomer(customerId)
                .setType(PaymentMethodListParams.Type.CARD).build()).getData();
    }

    /** Fija la tarjeta por defecto del Customer (la que cobrará las suscripciones). */
    public void setDefaultPaymentMethod(String customerId, String paymentMethodId) throws StripeException {
        Customer.retrieve(customerId).update(CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId).build())
                .build());
    }

    /** Desvincula (borra) una tarjeta guardada. */
    public void detachPaymentMethod(String paymentMethodId) throws StripeException {
        PaymentMethod.retrieve(paymentMethodId).detach();
    }

    /** Id del método de pago por defecto del Customer (el que cobra las suscripciones), o null. */
    public String defaultPaymentMethodId(String customerId) throws StripeException {
        Customer c = Customer.retrieve(customerId);
        return c.getInvoiceSettings() == null ? null : c.getInvoiceSettings().getDefaultPaymentMethod();
    }

    /** Método de pago por defecto o, si no hay default fijado, la primera tarjeta guardada (o null). */
    public String defaultOrFirstCardId(String customerId) throws StripeException {
        String def = defaultPaymentMethodId(customerId);
        if (def != null && !def.isBlank()) {
            return def;
        }
        List<PaymentMethod> cards = listCards(customerId);
        return cards.isEmpty() ? null : cards.get(0).getId();
    }

    /** Resultado de un cobro off-session: estado del PaymentIntent, su client_secret (para 3DS) y su id. */
    public record OffSessionResult(String id, String status, String clientSecret) {
    }

    /**
     * Cobra un pedido con una tarjeta GUARDADA sin interacción del usuario ({@code off_session + confirm}).
     * Si la tarjeta exige autenticación (3DS), Stripe lanza {@link CardException} con el PaymentIntent en
     * {@code requires_action}: se devuelve su {@code client_secret} para que el navegador complete la
     * autenticación y luego se confirme. Metadata {@code purpose=order} + {@code orderId} para distinguirlo
     * en el dashboard del ingreso por planes.
     */
    public OffSessionResult chargeSavedCardOffSession(String customerId, String paymentMethodId, long amountMinor,
            String currency, String orderId) throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setCustomer(customerId)
                .setPaymentMethod(paymentMethodId)
                .setAmount(amountMinor)
                .setCurrency(currency.toLowerCase())
                .setConfirm(true)
                .setOffSession(true)
                .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                .putMetadata(PURPOSE, "order").putMetadata("orderId", orderId)
                .build();
        try {
            PaymentIntent pi = PaymentIntent.create(params);
            return new OffSessionResult(pi.getId(), pi.getStatus(), pi.getClientSecret());
        } catch (CardException e) {
            PaymentIntent pi = e.getStripeError() != null ? e.getStripeError().getPaymentIntent() : null;
            if (pi != null) {
                return new OffSessionResult(pi.getId(), "requires_action", pi.getClientSecret());
            }
            throw e;
        }
    }

    /** Estado actual de un PaymentIntent (para confirmar tras completar el 3DS en el navegador). */
    public String paymentIntentStatus(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId).getStatus();
    }

    // =================================================================================================
    // Precios recurrentes (find-or-create) + Suscripción, MARCADA como pago de plan
    // =================================================================================================

    /** Resultado app-friendly de una operación de suscripción Stripe (sin exponer el SDK). */
    public record SubResult(String id, String status, Long periodStart, Long periodEnd) {
    }

    /**
     * Find-or-create de un Price recurrente. La {@code lookup_key} incluye importe+moneda+periodo, así un
     * cambio de precio del plan en admin genera AUTOMÁTICAMENTE un Price nuevo (los Price de Stripe son
     * inmutables). Idempotente: si ya existe ese importe/periodo, reutiliza el Price. El importe va en la
     * moneda de COBRO ya convertida (USD), no en CNY. Crea el Product inline la primera vez.
     */
    public String ensureRecurringPrice(String planCode, String period, long amountCents, String chargeCurrency,
            String planName) throws StripeException {
        String cur = (chargeCurrency == null || chargeCurrency.isBlank()) ? "usd" : chargeCurrency.toLowerCase();
        String key = ("nx_" + planCode + "_" + period + "_" + amountCents + "_" + cur).toLowerCase()
                .replaceAll("[^a-z0-9_]", "");
        List<Price> found = Price.list(PriceListParams.builder().addLookupKey(key).build()).getData();
        if (!found.isEmpty()) {
            return found.get(0).getId();
        }
        PriceCreateParams.Recurring.Interval interval = "YEARLY".equalsIgnoreCase(period)
                ? PriceCreateParams.Recurring.Interval.YEAR
                : PriceCreateParams.Recurring.Interval.MONTH;
        PriceCreateParams params = PriceCreateParams.builder().setCurrency(cur).setUnitAmount(amountCents)
                .setLookupKey(key).setTransferLookupKey(true)
                .setRecurring(PriceCreateParams.Recurring.builder().setInterval(interval).build())
                .setProductData(PriceCreateParams.ProductData.builder().setName(planName + " — " + period).build())
                .putMetadata(PLATFORM, platformId).putMetadata(PLAN_CODE, planCode).build();
        return Price.create(params).getId();
    }

    /**
     * Find-or-create de un TaxRate (IVA) por país+bps; devuelve su id o null si bps&lt;=0. El % viene de
     * {@code CountryTaxService.rateBpsFor(country)} (la misma config de IVA que los productos). Al adjuntarlo
     * a la suscripción, Stripe añade el IVA a cada factura (aparece desglosado en la factura).
     */
    public String ensureTaxRate(String country, int bps) throws StripeException {
        if (bps <= 0) {
            return null;
        }
        String tag = "nx_iva_" + (country == null ? "" : country.toLowerCase()) + "_" + bps;
        for (TaxRate tr : TaxRate.list(TaxRateListParams.builder().setActive(true).setLimit(100L).build()).getData()) {
            if (tr.getMetadata() != null && tag.equals(tr.getMetadata().get("nx_tag"))) {
                return tr.getId();
            }
        }
        TaxRateCreateParams.Builder b = TaxRateCreateParams.builder()
                .setDisplayName("IVA" + (country != null && !country.isBlank() ? " " + country.toUpperCase() : ""))
                .setPercentage(BigDecimal.valueOf(bps).movePointLeft(2)).setInclusive(false)
                .putMetadata("nx_tag", tag).putMetadata(PLATFORM, platformId);
        if (country != null && country.trim().length() == 2) {
            b.setCountry(country.trim().toUpperCase());
        }
        return TaxRate.create(b.build()).getId();
    }

    /**
     * Crea una suscripción recurrente cobrando YA con la tarjeta por defecto del Customer
     * ({@code ERROR_IF_INCOMPLETE}: si la tarjeta requiere 3DS o falla, lanza). Metadata marca el origen
     * como PLAN ({@code purpose=subscription}, {@code plan_code}, {@code user_id}). Si {@code taxRateId} no
     * es null, se aplica como IVA por defecto → Stripe lo añade a la factura.
     */
    public SubResult createSubscription(String customerId, String priceId, String defaultPaymentMethodId,
            String taxRateId, String planCode, String userId, String localSubscriptionId) throws StripeException {
        SubscriptionCreateParams.Builder b = SubscriptionCreateParams.builder().setCustomer(customerId)
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                .setProrationBehavior(SubscriptionCreateParams.ProrationBehavior.CREATE_PRORATIONS)
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.ERROR_IF_INCOMPLETE)
                .putMetadata(PLATFORM, platformId).putMetadata("env", platformEnv)
                .putMetadata(PURPOSE, SUBSCRIPTION).putMetadata(PLAN_CODE, planCode)
                .putMetadata("user_id", userId).putMetadata("subscription_id", localSubscriptionId);
        if (defaultPaymentMethodId != null && !defaultPaymentMethodId.isBlank()) {
            b.setDefaultPaymentMethod(defaultPaymentMethodId);
        }
        if (taxRateId != null && !taxRateId.isBlank()) {
            b.addDefaultTaxRate(taxRateId);
        }
        return toResult(Subscription.create(b.build()));
    }

    /**
     * Cambia el precio/plan de una suscripción existente.
     * <ul>
     *   <li><b>Subida (upgrade)</b>: {@code ALWAYS_INVOICE} → Stripe factura y COBRA de inmediato el
     *       prorrateo (diferencia por los días que quedan del periodo) contra la tarjeta por defecto.</li>
     *   <li><b>Bajada (downgrade)</b>: {@code NONE} → no cobra ahora; el periodo actual sigue al precio
     *       viejo (ya pagado) y el nuevo precio (menor) se aplica en la próxima renovación.</li>
     * </ul>
     */
    public SubResult changeSubscriptionPrice(String subscriptionId, String newPriceId, String planCode,
            SubscriptionUpdateParams.ProrationBehavior proration) throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);
        String itemId = sub.getItems().getData().get(0).getId();
        return toResult(sub.update(SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(newPriceId).build())
                .setProrationBehavior(proration)
                .putMetadata(PLAN_CODE, planCode).build()));
    }

    /** Cancela una suscripción: al final del periodo (atPeriodEnd=true) o de inmediato. */
    public SubResult cancelSubscription(String subscriptionId, boolean atPeriodEnd) throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);
        return toResult(atPeriodEnd
                ? sub.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build())
                : sub.cancel());
    }

    private SubResult toResult(Subscription s) {
        return new SubResult(s.getId(), s.getStatus(), s.getCurrentPeriodStart(), s.getCurrentPeriodEnd());
    }

    /** Resumen de factura (datos no sensibles) para el historial y el PDF propio del usuario. */
    public record InvoiceInfo(String id, String number, Long total, String currency, String status, Long created,
            String pdfUrl, String hostedUrl, long subtotal, long tax, Long periodStart, Long periodEnd,
            String lineDescription, String customerName, String customerEmail) {
    }

    /** Facturas del Customer (las más recientes primero), para el historial de facturación del perfil. */
    public List<InvoiceInfo> listInvoices(String customerId, int limit) throws StripeException {
        return Invoice.list(InvoiceListParams.builder().setCustomer(customerId).setLimit((long) Math.max(1, limit))
                .build()).getData().stream()
                .map(this::toInvoiceInfo)
                .toList();
    }

    /** Mapea una factura de Stripe al resumen propio, extrayendo subtotal, IVA, periodo y descripción de línea. */
    private InvoiceInfo toInvoiceInfo(Invoice inv) {
        String description = null;
        Long periodStart = null;
        Long periodEnd = null;
        if (inv.getLines() != null && inv.getLines().getData() != null && !inv.getLines().getData().isEmpty()) {
            InvoiceLineItem line = inv.getLines().getData().get(0);
            description = line.getDescription();
            if (line.getPeriod() != null) {
                periodStart = line.getPeriod().getStart();
                periodEnd = line.getPeriod().getEnd();
            }
        }
        long subtotal = inv.getSubtotal() != null ? inv.getSubtotal() : 0L;
        long tax = inv.getTax() != null ? inv.getTax() : 0L;
        return new InvoiceInfo(inv.getId(), inv.getNumber(), inv.getTotal(), inv.getCurrency(), inv.getStatus(),
                inv.getCreated(), inv.getInvoicePdf(), inv.getHostedInvoiceUrl(), subtotal, tax, periodStart, periodEnd,
                description, inv.getCustomerName(), inv.getCustomerEmail());
    }

    // =================================================================================================
    // Webhooks
    // =================================================================================================

    /** Verifica la firma del webhook y construye el evento. Lanza si la firma no es válida (fail-closed). */
    public Event constructWebhookEvent(String payload, String signatureHeader)
            throws SignatureVerificationException {
        return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
    }

    public boolean webhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
