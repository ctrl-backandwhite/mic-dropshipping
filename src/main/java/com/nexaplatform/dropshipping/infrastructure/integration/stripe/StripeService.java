package com.nexaplatform.dropshipping.infrastructure.integration.stripe;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    @PostConstruct
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
                .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                .putMetadata("purpose", "subscription")
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                        .putMetadata("purpose", "subscription").build())
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
                .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                .putMetadata("user_id", userId).build();
        return Customer.create(params);
    }

    /** Crea un SetupIntent para guardar una tarjeta con Stripe Elements; devuelve su client_secret. */
    public SetupIntent createSetupIntent(String customerId) throws StripeException {
        SetupIntentCreateParams params = SetupIntentCreateParams.builder().setCustomer(customerId)
                .addPaymentMethodType("card").putMetadata("platform", platformId)
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

    // =================================================================================================
    // Suscripción (cobro recurrente con la tarjeta por defecto), MARCADA como pago de plan
    // =================================================================================================

    /**
     * Crea una suscripción recurrente cobrando con la tarjeta por defecto del Customer. La metadata marca
     * el origen como PLAN ({@code purpose=subscription}, {@code plan_code}, {@code user_id}) para poder
     * distinguir el ingreso en Stripe. Expande {@code latest_invoice.payment_intent} para detectar 3DS.
     */
    public Subscription createSubscription(String customerId, String priceId, String defaultPaymentMethodId,
            String planCode, String userId, String localSubscriptionId) throws StripeException {
        SubscriptionCreateParams.Builder b = SubscriptionCreateParams.builder().setCustomer(customerId)
                .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                .setProrationBehavior(SubscriptionCreateParams.ProrationBehavior.CREATE_PRORATIONS)
                .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                .addExpand("latest_invoice.payment_intent")
                .putMetadata("platform", platformId).putMetadata("env", platformEnv)
                .putMetadata("purpose", "subscription").putMetadata("plan_code", planCode)
                .putMetadata("user_id", userId).putMetadata("subscription_id", localSubscriptionId);
        if (defaultPaymentMethodId != null && !defaultPaymentMethodId.isBlank()) {
            b.setDefaultPaymentMethod(defaultPaymentMethodId);
        }
        return Subscription.create(b.build());
    }

    /** Cambia el precio/plan de una suscripción existente con prorrateo (upgrade/downgrade). */
    public Subscription changeSubscriptionPrice(String subscriptionId, String newPriceId, String planCode)
            throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);
        String itemId = sub.getItems().getData().get(0).getId();
        return sub.update(SubscriptionUpdateParams.builder()
                .addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(newPriceId).build())
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                .putMetadata("plan_code", planCode).build());
    }

    /** Cancela una suscripción: al final del periodo (atPeriodEnd=true) o de inmediato. */
    public Subscription cancelSubscription(String subscriptionId, boolean atPeriodEnd) throws StripeException {
        Subscription sub = Subscription.retrieve(subscriptionId);
        if (atPeriodEnd) {
            return sub.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
        }
        return sub.cancel();
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
