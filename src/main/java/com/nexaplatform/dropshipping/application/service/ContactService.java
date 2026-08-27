package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Formulario público "Contáctanos": crea una notificación IN_APP en la bandeja de CADA administrador
 * con los datos del contacto y, además, envía un email a cada admin (con Reply-To = email del remitente,
 * para poder responder directamente) y un acuse de recibo profesional al remitente. No expone si el email
 * existe ni requiere sesión.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private static final int MAX_MESSAGE = 4000;

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final EmailQueueService emailQueue;

    @Transactional
    public void submit(String name, String email, String subject, String message) {
        String cleanEmail = email == null ? "" : email.trim();
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new BusinessException("CONTACT_INVALID", "Indica un email válido.");
        }
        if (cleanMessage.isBlank()) {
            throw new BusinessException("CONTACT_INVALID", "El mensaje no puede estar vacío.");
        }
        if (cleanMessage.length() > MAX_MESSAGE) {
            cleanMessage = cleanMessage.substring(0, MAX_MESSAGE);
        }
        String cleanName = name == null ? "" : name.trim();
        String cleanSubject = subject == null ? "" : subject.trim();

        String title = "Nueva solicitud de contacto" + (cleanSubject.isBlank() ? "" : ": " + cleanSubject);
        String body = "De: " + (cleanName.isBlank() ? cleanEmail : cleanName + " <" + cleanEmail + ">") + "\n\n"
                + cleanMessage;

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", cleanName);
        payload.put("email", cleanEmail);
        payload.put("subject", cleanSubject);
        payload.put("message", cleanMessage);

        List<User> admins = userRepository.findAll().stream().filter(u -> u.getRole() == UserRole.ADMIN).toList();
        for (User admin : admins) {
            notificationRepository.save(PlatformNotification.builder().userId(admin.getId()).title(title).body(body)
                    .eventType("CONTACT_REQUEST").channel("IN_APP").payload(payload).build());
        }

        notifyAdminsByEmail(admins, cleanName, cleanEmail, cleanSubject, cleanMessage, title);
        sendAcknowledgement(cleanName, cleanEmail, cleanSubject);

        log.info("::> [CONTACT] Solicitud de {} → notificados {} admin(s)", cleanEmail, admins.size());
    }

    /**
     * Respuesta del admin al usuario desde el panel: envía un email al {@code email} indicado con el
     * asunto y el mensaje redactados por el administrador. El Reply-To queda en el buzón de soporte.
     */
    @Transactional
    public void replyTo(String email, String subject, String message) {
        String cleanEmail = email == null ? "" : email.trim();
        String cleanSubject = subject == null ? "" : subject.trim();
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            throw new BusinessException("CONTACT_INVALID", "Indica un email válido.");
        }
        if (cleanSubject.isBlank()) {
            throw new BusinessException("CONTACT_INVALID", "El asunto no puede estar vacío.");
        }
        if (cleanMessage.isBlank()) {
            throw new BusinessException("CONTACT_INVALID", "El mensaje no puede estar vacío.");
        }
        if (cleanMessage.length() > MAX_MESSAGE) {
            cleanMessage = cleanMessage.substring(0, MAX_MESSAGE);
        }

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", cleanSubject);
        vars.put("bodyHtml", toHtml(cleanMessage));
        vars.put("footer", "NX036");
        vars.put("footerNote", "Respuesta de nuestro equipo de soporte a tu consulta.");
        emailQueue.enqueue(cleanEmail, cleanSubject, "emails/notification", vars);

        log.info("::> [CONTACT] Respuesta de admin enviada a {}", cleanEmail);
    }

    private void notifyAdminsByEmail(List<User> admins, String name, String senderEmail, String subject,
            String message, String title) {
        String sender = name.isBlank() ? senderEmail : name + " <" + senderEmail + ">";
        for (User admin : admins) {
            String adminEmail = admin.getEmail();
            if (adminEmail == null || adminEmail.isBlank()) {
                continue;
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("preheader", "Nueva solicitud del formulario de contacto de " + sender);
            vars.put("bodyHtml", toHtml(message));
            List<List<String>> details = new ArrayList<>();
            details.add(List.of("Nombre", name.isBlank() ? "—" : escape(name)));
            details.add(List.of("Email", escape(senderEmail)));
            details.add(List.of("Asunto", subject.isBlank() ? "—" : escape(subject)));
            vars.put("details", details);
            vars.put("footer", "NX036 · Panel de administración");
            vars.put("footerNote", "Responde a este correo para contestar directamente a " + senderEmail + ".");
            // Reply-To = email del remitente: el admin responde desde su bandeja y le llega al usuario.
            emailQueue.enqueue(adminEmail, senderEmail, title, "emails/notification", vars);
        }
    }

    private void sendAcknowledgement(String name, String senderEmail, String subject) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", name);
        vars.put("subject", subject);
        emailQueue.enqueue(senderEmail, "Hemos recibido tu mensaje — NX036", "emails/contact-ack", vars);
    }

    /** Escapa el texto plano del usuario y conserva los saltos de línea para su render en el email. */
    private static String toHtml(String text) {
        return escape(text).replace("\n", "<br/>");
    }

    /** Escapa los caracteres HTML de un valor de usuario (se renderiza con th:utext en la plantilla). */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
