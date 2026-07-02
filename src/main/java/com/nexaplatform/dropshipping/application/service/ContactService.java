package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Formulario público "Contáctanos": crea una notificación IN_APP en la bandeja de CADA administrador
 * con los datos del contacto, para que el equipo gestione la solicitud desde el panel admin. No expone
 * si el email existe ni requiere sesión.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private static final int MAX_MESSAGE = 4000;

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

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
        log.info("::> [CONTACT] Solicitud de {} → notificados {} admin(s)", cleanEmail, admins.size());
    }
}
