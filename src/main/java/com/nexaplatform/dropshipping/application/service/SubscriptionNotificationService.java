package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.SubscriptionCancelReminderEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionEmailLabel;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionPlanLabel;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
            notificationRepository.save(PlatformNotification.builder().userId(userId).eventType("PLAN_PAYMENT_FAILED")
                    .channel("IN_APP").title(TITLE).body(BODY).build());
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
            vars.put("bodyHtml",
                    "No hemos podido procesar el cobro de la cuota de tu plan. "
                            + "Para mantener tu plan activo y no perder el acceso, entra en tu perfil y revisa o "
                            + "actualiza tu método de pago. Volveremos a intentar el cobro automáticamente.");
            vars.put("ctaUrl", baseUrl + "/profile");
            vars.put("ctaLabel", "Revisar mi método de pago");
            vars.put("footer", "NX036");
            emailQueue.enqueue(email, "Problema con el pago de tu plan — NX036", "emails/notification", vars);
        } catch (RuntimeException e) {
            log.warn("::> [BILLING] no se pudo encolar el email de pago fallido user={}: {}", userId, e.getMessage());
        }
        log.info("::> [BILLING] Aviso de pago fallido enviado user={} stripeSub={}", userId, stripeSubscriptionId);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneOffset.UTC);

    /**
     * Email de confirmación al contratar un plan, en el idioma del usuario. {@code trial=true} usa el texto
     * de la prueba de 15 días (con su fecha de fin); {@code trial=false} el de un plan de pago. Best-effort:
     * cualquier fallo se registra y no rompe la contratación.
     */
    public void planActivated(UUID userId, String planCode, Instant periodEnd, boolean trial) {
        planActivated(userId, planCode, periodEnd, trial, null, null);
    }

    /**
     * Recordatorio (uno al día en los 3 días previos) de que un plan CANCELADO a fin de periodo se acerca a
     * su fecha de cancelación. Email + notificación in-app, en el idioma del usuario. Best-effort.
     */
    public void planCancelReminder(UUID userId, String planCode, Instant cancelAt) {
        try {
            UserEntity user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                return;
            }
            String lang = user.getLanguage();
            String plan = SubscriptionPlanLabel.planName(planCode, lang);
            String date = cancelAt != null ? DATE.format(cancelAt) : "";
            String body = SubscriptionCancelReminderEmailLabel.BODY.of(lang).replace("{plan}", plan).replace("{date}",
                    date);
            try {
                notificationRepository.save(PlatformNotification.builder().userId(userId)
                        .eventType("PLAN_CANCEL_REMINDER").channel("IN_APP")
                        .title(SubscriptionCancelReminderEmailLabel.TITLE.of(lang)).body(body).build());
            } catch (RuntimeException e) {
                log.warn("::> [BILLING] no se pudo crear la notificación in-app de recordatorio user={}: {}", userId,
                        e.getMessage());
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", SubscriptionCancelReminderEmailLabel.TITLE.of(lang));
            vars.put("preheader", SubscriptionCancelReminderEmailLabel.TITLE.of(lang));
            vars.put("bodyHtml", body);
            vars.put("ctaUrl", baseUrl + "/profile");
            vars.put("ctaLabel", SubscriptionCancelReminderEmailLabel.CTA.of(lang));
            vars.put("footer", "NX036");
            emailQueue.enqueue(user.getEmail(), SubscriptionCancelReminderEmailLabel.SUBJECT.of(lang),
                    "emails/notification", vars);
            log.info("::> [BILLING] Recordatorio de cancelación enviado user={} plan={} cancelAt={}", userId, plan,
                    cancelAt);
        } catch (RuntimeException e) {
            log.warn("::> [BILLING] no se pudo enviar el recordatorio de cancelación user={}: {}", userId,
                    e.getMessage());
        }
    }

    /**
     * Como {@link #planActivated(UUID, String, Instant, boolean)} pero adjuntando la FACTURA (PDF) del plan
     * al correo (solo planes de pago; la prueba no genera factura). Además crea una notificación in-app
     * (campana) para que la contratación aparezca en el buzón del usuario. Todo best-effort.
     */
    public void planActivated(UUID userId, String planCode, Instant periodEnd, boolean trial, byte[] invoicePdf,
            String invoiceFilename) {
        try {
            UserEntity user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                return;
            }
            String lang = user.getLanguage();
            String name = user.getDisplayName() != null ? user.getDisplayName() : "";
            String date = periodEnd != null ? DATE.format(periodEnd) : "";
            // Nombre del plan traducido al idioma del usuario (no el nombre crudo de la BD "Starter").
            String plan = SubscriptionPlanLabel.planName(planCode, lang);
            String tpl = trial ? SubscriptionEmailLabel.BODY_TRIAL.of(lang) : SubscriptionEmailLabel.BODY_PAID.of(lang);
            String body = tpl.replace("{name}", name).replace("{plan}", plan).replace("{date}", date).replace("  ", " ")
                    .replace(" ,", ",");

            // Notificación in-app (campana): la contratación queda en el buzón del usuario.
            try {
                notificationRepository.save(PlatformNotification.builder().userId(userId).eventType("PLAN_ACTIVATED")
                        .channel("IN_APP").title(SubscriptionEmailLabel.TITLE.of(lang)).body(body).build());
            } catch (RuntimeException e) {
                log.warn("::> [BILLING] no se pudo crear la notificación in-app de plan contratado user={}: {}", userId,
                        e.getMessage());
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", SubscriptionEmailLabel.TITLE.of(lang));
            vars.put("preheader", SubscriptionEmailLabel.TITLE.of(lang));
            vars.put("bodyHtml", body);
            vars.put("ctaUrl", baseUrl + "/profile");
            vars.put("ctaLabel", SubscriptionEmailLabel.CTA.of(lang));
            vars.put("footer", "NX036");
            String subject = SubscriptionEmailLabel.SUBJECT.of(lang);
            if (invoicePdf != null && invoicePdf.length > 0) {
                emailQueue.enqueueWithAttachment(user.getEmail(), subject, "emails/notification", vars, invoicePdf,
                        invoiceFilename != null ? invoiceFilename : "factura.pdf");
            } else {
                emailQueue.enqueue(user.getEmail(), subject, "emails/notification", vars);
            }
            log.info("::> [BILLING] Email de plan contratado encolado user={} plan={} trial={} conFactura={}", userId,
                    plan, trial, invoicePdf != null);
        } catch (RuntimeException e) {
            log.warn("::> [BILLING] no se pudo encolar el email de plan contratado user={}: {}", userId,
                    e.getMessage());
        }
    }
}
