package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.ContactService;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Formulario público "Contáctanos".
 *
 * <p>Es un endpoint SIN sesión: cualquiera puede escribir en él. Lo que se fija aquí es que valide antes
 * de tocar nada (un mensaje vacío o un email sin arroba no debe generar avisos a todos los admins), que
 * el texto del usuario se escape antes de meterlo en un correo HTML, y que la solicitud llegue a TODOS
 * los administradores más el acuse de recibo al remitente.
 */
class Cov08ContactServiceTest {

    private UserRepository userRepository;
    private NotificationRepository notificationRepository;
    private EmailQueueService emailQueue;
    private ContactService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        emailQueue = mock(EmailQueueService.class);
        service = new ContactService(userRepository, notificationRepository, emailQueue);
    }

    private static User user(UserRole role, String email) {
        return User.builder().id(UUID.randomUUID()).role(role).email(email).build();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static ArgumentCaptor<PlatformNotification> notificationCaptor() {
        return ArgumentCaptor.forClass(PlatformNotification.class);
    }

    private void adminExiste(String email) {
        when(userRepository.findAll()).thenReturn(List.of(user(UserRole.ADMIN, email)));
    }

    // ─────────────────────── validación ───────────────────────

    @Test
    void unEmailSinArrobaSeRechazaAntesDeAvisarANadie() {
        assertThatThrownBy(() -> service.submit("Ana", "ana.example.com", "Hola", "Mensaje"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(notificationRepository, emailQueue);
    }

    @Test
    void unEmailAusenteSeRechaza() {
        assertThatThrownBy(() -> service.submit("Ana", null, "Hola", "Mensaje"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unMensajeVacioSeRechaza() {
        // Solo espacios: sin esto, cada admin recibiría una notificación sin contenido.
        assertThatThrownBy(() -> service.submit("Ana", "ana@example.com", "Hola", "   "))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(notificationRepository, emailQueue);
    }

    @Test
    void unMensajeDesmesuradoSeRecortaACuatroMilCaracteres() {
        // Sin el recorte, un envío automatizado podría reventar la columna de la notificación.
        adminExiste("admin@nx.local");

        service.submit("Ana", "ana@example.com", "Asunto", "x".repeat(5000));

        ArgumentCaptor<PlatformNotification> saved = notificationCaptor();
        verify(notificationRepository).save(saved.capture());
        assertThat((String) saved.getValue().getPayload().get("message")).hasSize(4000);
    }

    // ─────────────────────── reparto ───────────────────────

    @Test
    void cadaAdministradorRecibeLaSolicitudYSoloLosAdministradores() {
        when(userRepository.findAll()).thenReturn(List.of(user(UserRole.ADMIN, "a1@nx.local"),
                user(UserRole.ADMIN, "a2@nx.local"), user(UserRole.USER, "cliente@nx.local")));

        service.submit("Ana", "ana@example.com", "Duda", "Tengo una duda");

        verify(notificationRepository, times(2)).save(any(PlatformNotification.class));
        // Dos correos a admins (con Reply-To) + el acuse de recibo al remitente (sin Reply-To).
        verify(emailQueue, times(2)).enqueue(anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(emailQueue).enqueue(eq("ana@example.com"), anyString(), eq("emails/contact-ack"), anyMap());
    }

    @Test
    void elCorreoAlAdminLlevaComoReplyToElEmailDelRemitente() {
        // Es lo que permite al admin responder desde su bandeja y que la respuesta le llegue al usuario.
        adminExiste("admin@nx.local");

        service.submit("Ana", "ana@example.com", "Duda", "Tengo una duda");

        verify(emailQueue).enqueue(eq("admin@nx.local"), eq("ana@example.com"), anyString(),
                eq("emails/notification"), anyMap());
    }

    @Test
    void unAdminSinEmailNoBloqueaElRestoDelReparto() {
        when(userRepository.findAll())
                .thenReturn(List.of(user(UserRole.ADMIN, null), user(UserRole.ADMIN, "   ")));

        service.submit("Ana", "ana@example.com", "Duda", "Tengo una duda");

        // Notificación en la bandeja del panel para los dos, pero ningún correo con Reply-To.
        verify(notificationRepository, times(2)).save(any(PlatformNotification.class));
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void sinNombreLaSolicitudSeIdentificaPorElEmail() {
        adminExiste("admin@nx.local");

        service.submit("  ", "ana@example.com", "Duda", "Tengo una duda");

        ArgumentCaptor<PlatformNotification> saved = notificationCaptor();
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getBody()).startsWith("De: ana@example.com");
    }

    @Test
    void sinAsuntoElTituloNoArrastraUnSeparadorSuelto() {
        adminExiste("admin@nx.local");

        service.submit("Ana", "ana@example.com", null, "Tengo una duda");

        ArgumentCaptor<PlatformNotification> saved = notificationCaptor();
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Nueva solicitud de contacto");
    }

    @Test
    void elTextoDelUsuarioSeEscapaAntesDeMeterloEnElCorreo() {
        // El cuerpo se pinta con th:utext: sin escapar, cualquiera podría inyectar HTML en la bandeja
        // del administrador desde un formulario público.
        adminExiste("admin@nx.local");

        service.submit("<b>Ana</b>", "ana@example.com", "Duda", "Hola <script>alert(1)</script>\nadios");

        ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
        verify(emailQueue).enqueue(anyString(), anyString(), anyString(), anyString(), vars.capture());
        assertThat((String) vars.getValue().get("bodyHtml")).doesNotContain("<script>")
                .contains("&lt;script&gt;").contains("<br/>");
        @SuppressWarnings("unchecked")
        List<List<String>> detalles = (List<List<String>>) vars.getValue().get("details");
        assertThat(detalles.get(0).get(1)).isEqualTo("&lt;b&gt;Ana&lt;/b&gt;");
    }

    // ─────────────────────── respuesta del admin ───────────────────────

    @Test
    void laRespuestaDelAdminExigeDestinatarioValido() {
        assertThatThrownBy(() -> service.replyTo("sin-arroba", "Asunto", "Cuerpo"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(emailQueue);
    }

    @Test
    void laRespuestaDelAdminExigeAsuntoYMensaje() {
        assertThatThrownBy(() -> service.replyTo("ana@example.com", "  ", "Cuerpo"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replyTo("ana@example.com", "Asunto", "  "))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(emailQueue);
    }

    @Test
    void laRespuestaDelAdminSeEnviaAlUsuarioConLaPlantillaDeNotificacion() {
        service.replyTo("  ana@example.com  ", "Sobre tu pedido", "Ya esta resuelto");

        ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
        verify(emailQueue).enqueue(eq("ana@example.com"), eq("Sobre tu pedido"), eq("emails/notification"),
                vars.capture());
        assertThat(vars.getValue()).containsEntry("title", "Sobre tu pedido")
                .containsEntry("bodyHtml", "Ya esta resuelto");
    }

    @Test
    void laRespuestaDelAdminTambienSeRecortaACuatroMilCaracteres() {
        service.replyTo("ana@example.com", "Asunto", "y".repeat(4500));

        ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
        verify(emailQueue).enqueue(anyString(), anyString(), anyString(), vars.capture());
        assertThat((String) vars.getValue().get("bodyHtml")).hasSize(4000);
    }
}
