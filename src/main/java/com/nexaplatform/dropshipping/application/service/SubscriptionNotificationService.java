package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.repository.CustomerSubscriptionRepository;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Avisos al usuario sobre el ciclo de vida de su suscripción/plan. Hoy cubre el FALLO de cobro recurrente
 * de la suscripción (evento {@code invoice.payment_failed} de Stripe): notifica in-app (campana) y por
 * email pidiendo revisar/actualizar el método de pago en el perfil. Todo va en try/catch para no bloquear
 * el procesamiento del webhook si la notificación falla.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionNotificationService {

    private final CustomerSubscriptionRepository customerSubscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailQueueService emailQueue;

    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String baseUrl;

    private static final String TITLE = "Problema con el pago de tu plan";
    private static final String BODY = "No hemos podido cobrar la cuota de tu plan. Para no perder el acceso, "
            + "revisa o actualiza tu método de pago en tu perfil.";

    /**
     * Notifica al dueño de la suscripción de Stripe que su cobro recurrente ha fallado. Resuelve el usuario
     * a partir del id de suscripción de Stripe (fila local {@link CustomerSubscription}); si no hay
     * coincidencia, no hace nada (webhook de una suscripción que no gestionamos).
     */
    public void planPaymentFailed(String stripeSubscriptionId) {
        if (stripeSubscriptionId == null || stripeSubscriptionId.isBlank()
                || "null".equalsIgnoreCase(stripeSubscriptionId)) {
            return;
        }
        Optional<CustomerSubscription> subOpt = customerSubscriptionRepository
                .findByStripeSubscriptionId(stripeSubscriptionId);
        if (subOpt.isEmpty() || subOpt.get().getUserId() == null) {
            log.info("::> [BILLING] payment_failed sin suscripción local para stripeSub={}", stripeSubscriptionId);
            return;
        }
        UUID userId = subOpt.get().getUserId();

        // Notificación in-app (campana).
        try {
            notificationRepository.save(PlatformNotification.builder().userId(userId)
                    .eventType("PLAN_PAYMENT_FAILED").channel("IN_APP").title(TITLE).body(BODY).build());
        } catch (RuntimeException e) {
            log.warn("::> [BILLING] no se pudo crear la notificación in-app de pago fallido user={}: {}", userId,
                    e.getMessage());
        }

        // Email profesional pidiendo revisar/actualizar el método de pago.
        try {
            UserEntity user = userRepository.findById(userId).orElse(null);
            String email = user != null ? user.getEmail() : null;
            if (email == null || email.isBlank()) {
                return;
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", TITLE);
            vars.put("preheader", TITLE);
            vars.put("bodyHtml", "No hemos podido procesar el cobro de la cuota de tu plan. "
                    + "Para mantener tu plan activo y no perder el acceso, entra en tu perfil y revisa o "
                    + "actualiza tu método de pago. Volveremos a intentar el cobro automáticamente.");
            vars.put("ctaUrl", baseUrl + "/profile");
            vars.put("ctaLabel", "Revisar mi método de pago");
            vars.put("footer", "NX036 LTD");
            emailQueue.enqueue(email, "Problema con el pago de tu plan — NX036 LTD", "emails/notification",
                    vars);
        } catch (RuntimeException e) {
            log.warn("::> [BILLING] no se pudo encolar el email de pago fallido user={}: {}", userId, e.getMessage());
        }
        log.info("::> [BILLING] Aviso de pago fallido enviado user={} stripeSub={}", userId, stripeSubscriptionId);
    }
}
