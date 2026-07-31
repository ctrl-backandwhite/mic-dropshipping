package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PlanFeatureEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SubscriptionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Aplica los LÍMITES por plan (plan_feature: {@code max_shops}, {@code max_products_sync},
 * {@code max_api_requests_day}). El límite efectivo es el del plan ACTIVO del usuario; si no tiene
 * suscripción activa, se usa el plan FREE (todo usuario tiene al menos los límites del gratis).
 */
@Service
@RequiredArgsConstructor
public class PlanLimitService {

    private final CustomerSubscriptionRepository subscriptionRepository;
    private final SubscriptionPlanRepository planRepository;

    /** Límite del feature para el usuario (plan activo, o FREE si no tiene); -1 = sin límite/desconocido. */
    // Sin @Transactional propia: sólo se llama desde assertWithinLimit, que ya abre la lectura.
    public long limitFor(UUID userId, String featureKey) {
        UUID activePlanId = subscriptionRepository.findByUserId(userId).stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE || s.getStatus() == SubscriptionStatus.TRIALING)
                .map(CustomerSubscription::getPlanId).findFirst().orElse(null);
        SubscriptionPlanEntity plan = (activePlanId != null ? planRepository.findById(activePlanId) : Optional
                .<SubscriptionPlanEntity>empty()).or(() -> planRepository.findByCode("FREE")).orElse(null);
        if (plan == null || plan.getFeatures() == null) {
            return -1;
        }
        return plan.getFeatures().stream()
                .filter(f -> featureKey.equalsIgnoreCase(f.getFeatureKey()) && f.getIntValue() != null)
                .map(PlanFeatureEntity::getIntValue).findFirst().orElse(-1L);
    }

    /** Lanza {@link BusinessException} si añadir uno más superaría el límite del plan. */
    // Abre la lectura aquí: limitFor es autoinvocación y su @Transactional no se aplica.
    @Transactional(readOnly = true)
    public void assertWithinLimit(UUID userId, String featureKey, long currentCount) {
        long limit = limitFor(userId, featureKey);
        if (limit >= 0 && currentCount >= limit) {
            throw new BusinessException(
                    "Has alcanzado el límite de tu plan (" + limit + "). Mejora tu plan para añadir más.");
        }
    }
}
