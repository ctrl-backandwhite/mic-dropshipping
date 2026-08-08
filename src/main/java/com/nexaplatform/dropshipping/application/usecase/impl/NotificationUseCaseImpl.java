package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Notification use case (DROP-11). Operates on the {@link PlatformNotification}
 * model and delegates persistence to the domain port. Holds the logic that used
 * to live in {@code PlatformExtrasService}: listing, counting unread, and the
 * idempotent mark-read / mark-all-read transitions (a notification already read
 * is left untouched).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationUseCaseImpl implements NotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final EmailQueueService emailQueue;

    @Override
    @Transactional(readOnly = true)
    public List<PlatformNotification> myNotifications(UUID userId, Folder folder) {
        Folder f = folder != null ? folder : Folder.INBOX;
        return notificationRepository.findByUserId(userId).stream().filter(n -> switch (f) {
            case INBOX -> n.getArchivedAt() == null && n.getDeletedAt() == null;
            case ARCHIVED -> n.getArchivedAt() != null && n.getDeletedAt() == null;
            case TRASH -> n.getDeletedAt() != null;
        }).toList();
    }

    @Override
    @Transactional
    public void archive(UUID id, UUID ownerUserId) {
        PlatformNotification n = getOwned(id, ownerUserId);
        n.setArchivedAt(Instant.now());
        n.setDeletedAt(null);
        notificationRepository.update(n);
    }

    @Override
    @Transactional
    public void unarchive(UUID id, UUID ownerUserId) {
        PlatformNotification n = getOwned(id, ownerUserId);
        n.setArchivedAt(null);
        notificationRepository.update(n);
    }

    @Override
    @Transactional
    public void moveToTrash(UUID id, UUID ownerUserId) {
        PlatformNotification n = getOwned(id, ownerUserId);
        n.setDeletedAt(Instant.now());
        notificationRepository.update(n);
    }

    @Override
    @Transactional
    public void restore(UUID id, UUID ownerUserId) {
        PlatformNotification n = getOwned(id, ownerUserId);
        n.setDeletedAt(null);
        notificationRepository.update(n);
    }

    @Override
    @Transactional
    public void deletePermanently(UUID id, UUID ownerUserId) {
        getOwned(id, ownerUserId); // valida propiedad antes de borrar físicamente
        notificationRepository.delete(id);
    }

    /**
     * Carga una notificación verificando que pertenece a {@code ownerUserId}. Si no existe o es de otro
     * usuario, lanza NotFound (mismo error para ambos casos → no revela la existencia de notificaciones
     * ajenas). Blinda las mutaciones por id contra IDOR entre buzones.
     */
    private PlatformNotification getOwned(UUID id, UUID ownerUserId) {
        PlatformNotification n = notificationRepository.getById(id);
        if (Objects.isNull(n) || ownerUserId == null || !ownerUserId.equals(n.getUserId())) {
            throw new NotFoundException("Notificación no encontrada");
        }
        return n;
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCount unreadCount(UUID userId) {
        return UnreadCount.builder().count(notificationRepository.countUnreadByUserId(userId)).build();
    }

    @Override
    @Transactional
    public void markRead(UUID id, UUID ownerUserId) {
        PlatformNotification model = notificationRepository.getById(id);
        if (Objects.isNull(model) || ownerUserId == null || !ownerUserId.equals(model.getUserId())) {
            return; // inexistente o de otro usuario → no-op (sin filtrar existencia)
        }
        boolean changed = false;
        if (model.getReadAt() == null) {
            model.setReadAt(Instant.now());
            changed = true;
        }
        // Acuse de recibo del flujo de gestión: al abrirla por primera vez pasa de NEW a RECEIVED.
        if (model.getStatus() == null || "NEW".equals(model.getStatus())) {
            model.setStatus(Status.RECEIVED.name());
            changed = true;
        }
        if (changed) {
            notificationRepository.update(model);
        }
    }

    @Override
    @Transactional
    public void setStatus(UUID id, UUID ownerUserId, Status status) {
        PlatformNotification model = getOwned(id, ownerUserId);
        Status target = status == null ? Status.RECEIVED : status;
        model.setStatus(target.name());
        // Al mover a RECEIVED o más allá, la damos por leída (coherencia con el acuse de recibo).
        if (model.getReadAt() == null) {
            model.setReadAt(Instant.now());
        }
        notificationRepository.update(model);
        // Avisa al solicitante de que su incidencia se está gestionando (IN_PROGRESS/WAITING/RESOLVED).
        notifyRequesterOfStatus(model, target);
    }

    /**
     * Notifica al solicitante de una solicitud de contacto/soporte del cambio de estado de su incidencia:
     * email al remitente (payload.email) y, si es usuario registrado, también una notificación en su buzón.
     * Solo para IN_PROGRESS / WAITING / RESOLVED (NEW/RECEIVED ya se cubren con el acuse de recibo inicial).
     */
    private void notifyRequesterOfStatus(PlatformNotification model, Status status) {
        StatusMail mail = StatusMail.forStatus(status);
        if (mail == null || model.getPayload() == null) {
            return;
        }
        Object emailObj = model.getPayload().get("email");
        String email = emailObj == null ? "" : emailObj.toString().trim();
        if (email.isBlank() || !email.contains("@")) {
            return;
        }
        Object subjObj = model.getPayload().get("subject");
        String subject = subjObj != null && !subjObj.toString().isBlank() ? subjObj.toString() : model.getTitle();
        String body = String.format(mail.body, subject);
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", mail.title);
        vars.put("bodyHtml", "<p>" + escapeHtml(body) + "</p>");
        vars.put("footer", "NX036 · Soporte");
        vars.put("footerNote", "Actualización del estado de tu solicitud de soporte.");
        emailQueue.enqueue(email, mail.title, "emails/notification", vars);
        // Buzón in-app si el solicitante tiene cuenta.
        userRepository.findByEmail(email.toLowerCase())
                .ifPresent(u -> create(u.getId(), mail.title, body, "SUPPORT_STATUS_" + status.name()));
        log.info("::> [SUPPORT] Aviso de estado {} enviado a {}", status, email);
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Contenido (asunto + cuerpo) del email de aviso al solicitante por cada estado gestionable. */
    private enum StatusMail {
        IN_PROGRESS("Tu solicitud está en proceso",
                "Hemos comenzado a gestionar tu solicitud «%s». Te mantendremos informado del progreso."),
        WAITING("Necesitamos más información",
                "Para continuar con tu solicitud «%s» necesitamos algunos datos adicionales. "
                        + "Por favor, responde a este correo con la información."),
        RESOLVED("Tu solicitud ha sido resuelta",
                "Tu solicitud «%s» ha quedado resuelta. Si necesitas cualquier otra cosa, estaremos "
                        + "encantados de ayudarte.");

        private final String title;
        private final String body;

        StatusMail(String title, String body) {
            this.title = title;
            this.body = body;
        }

        static StatusMail forStatus(Status s) {
            try {
                return valueOf(s.name());
            } catch (IllegalArgumentException e) {
                return null; // NEW / RECEIVED → sin email de aviso
            }
        }
    }

    @Override
    @Transactional
    public void markAllRead(UUID userId) {
        for (PlatformNotification model : notificationRepository.findByUserId(userId)) {
            if (model.getReadAt() == null) {
                model.setReadAt(Instant.now());
                notificationRepository.update(model);
            }
        }
    }

    @Override
    @Transactional
    public int sendAdminNotification(String target, String title, String body) {
        if (target == null || target.isBlank() || "all".equalsIgnoreCase(target.trim())) {
            int n = 0;
            for (User u : userRepository.findAll()) {
                create(u.getId(), title, body, "ADMIN_BROADCAST");
                n++;
            }
            return n;
        }
        User user = userRepository.findByEmail(target.trim().toLowerCase())
                .orElseThrow(() -> new BusinessException("Usuario no encontrado: " + target));
        create(user.getId(), title, body, "ADMIN_MESSAGE");
        return 1;
    }

    private void create(UUID userId, String title, String body, String eventType) {
        notificationRepository.save(PlatformNotification.builder().userId(userId).title(title).body(body)
                .eventType(eventType).channel("IN_APP").build());
    }
}
