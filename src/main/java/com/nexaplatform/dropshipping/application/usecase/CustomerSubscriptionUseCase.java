package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Billing aggregate root {@link CustomerSubscription}.
 * Absorbs the subscription logic that used to live in {@code SubscriptionService}
 * (admin listing, creation, per-user listing, Stripe checkout) and operates on the
 * domain model. The plan-side helpers stay here because they share the Billing
 * cluster's collaborators; mutations are delegated to {@link SubscriptionPlanUseCase}.
 */
public interface CustomerSubscriptionUseCase extends BaseUseCase<CustomerSubscription, CustomerSubscription, UUID> {

    /** Admin listing of all subscriptions, optionally filtered by status name. */
    List<CustomerSubscription> listAdminSubscriptions(String status);

    /** Creates an ACTIVE subscription for the user/plan/period and returns the saved model. */
    CustomerSubscription createSubscription(UUID userId, String planCode, String billingPeriod);

    /** All subscriptions owned by the given user (storefront listing). */
    List<CustomerSubscription> listForUser(UUID userId);

    /**
     * Starts a subscription checkout for the user. In dev mode (Stripe disabled)
     * the subscription is created directly and a local success URL is returned;
     * otherwise a provider checkout session is created and its URL/id are returned.
     */
    SubscribeResult subscribe(UUID userId, String planCode, String period) throws Exception;

    /** Admin plan listing, ordered by position, as domain models. */
    List<SubscriptionPlan> listAdminPlans();

    /** Applies a partial update to the plan identified by {@code code} (delegated to the plan use case). */
    SubscriptionPlan updatePlan(String code, SubscriptionPlan changes);

    /** Active public plan entities ordered by position (kept as entities for the feature-derived limits). */
    List<SubscriptionPlanEntity> listPublicPlans();

    // ---- Métodos de pago en el perfil (tarjeta guardada vía Stripe Elements) ----

    /** Tarjeta guardada del usuario (datos no sensibles). */
    record CardInfo(String id, String brand, String last4, Long expMonth, Long expYear, boolean isDefault) {
    }

    /** Config pública de billing para el frontend. */
    record BillingConfigInfo(String publishableKey, boolean enabled) {
    }

    /** Publishable key + estado de Stripe (para inicializar Elements en el front). */
    BillingConfigInfo billingConfig();

    /** Crea un SetupIntent para que el usuario guarde una tarjeta; devuelve su client_secret. */
    String createSetupIntentSecret(UUID userId) throws Exception;

    /** Tarjetas guardadas del usuario. */
    List<CardInfo> listCards(UUID userId) throws Exception;

    /** Fija la tarjeta por defecto (la que cobra las suscripciones). */
    void setDefaultCard(UUID userId, String paymentMethodId) throws Exception;

    /** Borra (desvincula) una tarjeta guardada. */
    void deleteCard(UUID userId, String paymentMethodId) throws Exception;

    // ---- Contratación de plan con la tarjeta guardada ----

    /** Resultado de contratar: id de suscripción Stripe (o local si es gratis) + estado normalizado. */
    record SubscribeOutcome(String subscriptionId, String status) {
    }

    /**
     * Contrata un plan cobrando con la tarjeta por defecto del usuario. El precio del plan está en CNY
     * (moneda de 1688) y se convierte a USD para el cobro en Stripe (igual que los productos). Plan gratis
     * (importe 0) → suscripción ACTIVE directa sin Stripe. Asocia/actualiza la CustomerSubscription.
     */
    SubscribeOutcome subscribeWithSavedCard(UUID userId, String planCode, String period) throws Exception;

    /** Suscripción "vigente" del usuario (la más reciente no cancelada), o null si no tiene. */
    CustomerSubscription currentSubscription(UUID userId);

    /** Cancela la suscripción vigente del usuario al final del periodo. */
    void cancelMySubscription(UUID userId) throws Exception;

    /**
     * Sincroniza la suscripción local desde un evento de Stripe (webhook): estado, fin de periodo y
     * cancelación programada. Cubre renovación, fallo de cobro (PAST_DUE) y cancelación desde Stripe.
     */
    void syncFromStripe(String stripeSubscriptionId, String stripeStatus, Long currentPeriodEnd, Long cancelAtEpoch);
}
