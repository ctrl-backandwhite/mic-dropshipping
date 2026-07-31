package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase.Folder;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase.Status;
import com.nexaplatform.dropshipping.application.usecase.impl.NotificationUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Buzón de notificaciones: carpetas, propiedad de cada mensaje y avisos de estado al solicitante.
 *
 * <p>Lo que se protege aquí es que nadie toque el buzón de otro (las mutaciones van por identificador)
 * y que el flujo tipo ticket avise por correo cuando de verdad hay algo que contar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov03NotificationMailboxTest {

    @Mock
    NotificationRepository notificationRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    EmailQueueService emailQueue;

    @InjectMocks
    NotificationUseCaseImpl useCase;

    private final UUID ownerId = UUID.randomUUID();

    private PlatformNotification propia(UUID id) {
        return PlatformNotification.builder().id(id).userId(ownerId).title("Contacto").build();
    }

    /* ==================== carpetas ==================== */

    @Test
    void recibidosDejaFueraLoArchivadoYLoQueEstaEnLaPapelera() {
        PlatformNotification enBandeja = PlatformNotification.builder().userId(ownerId).title("A").build();
        PlatformNotification archivada = PlatformNotification.builder().userId(ownerId).title("B")
                .archivedAt(Instant.now()).build();
        PlatformNotification borrada = PlatformNotification.builder().userId(ownerId).title("C")
                .deletedAt(Instant.now()).build();
        when(notificationRepository.findByUserId(ownerId)).thenReturn(List.of(enBandeja, archivada, borrada));

        assertThat(useCase.myNotifications(ownerId, Folder.INBOX)).containsExactly(enBandeja);
    }

    @Test
    void archivadosNoMuestraLoQueAdemasSeMandoALaPapelera() {
        // Una notificación archivada y luego borrada pertenece a la papelera: si saliera en las dos
        // carpetas, restaurarla desde Archivados la duplicaría a ojos del usuario.
        PlatformNotification archivada = PlatformNotification.builder().userId(ownerId).title("B")
                .archivedAt(Instant.now()).build();
        PlatformNotification archivadaYBorrada = PlatformNotification.builder().userId(ownerId).title("C")
                .archivedAt(Instant.now()).deletedAt(Instant.now()).build();
        when(notificationRepository.findByUserId(ownerId)).thenReturn(List.of(archivada, archivadaYBorrada));

        assertThat(useCase.myNotifications(ownerId, Folder.ARCHIVED)).containsExactly(archivada);
        assertThat(useCase.myNotifications(ownerId, Folder.TRASH)).containsExactly(archivadaYBorrada);
    }

    @Test
    void sinIndicarCarpetaSeAbreRecibidos() {
        PlatformNotification enBandeja = PlatformNotification.builder().userId(ownerId).build();
        PlatformNotification borrada = PlatformNotification.builder().userId(ownerId).deletedAt(Instant.now()).build();
        when(notificationRepository.findByUserId(ownerId)).thenReturn(List.of(enBandeja, borrada));

        assertThat(useCase.myNotifications(ownerId, null)).containsExactly(enBandeja);
    }

    /* ==================== transiciones de carpeta ==================== */

    @Test
    void archivarSacaDeLaPapeleraParaQueNoQuedeEnDosSitios() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setDeletedAt(Instant.now());
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.archive(id, ownerId);

        assertThat(n.getArchivedAt()).isNotNull();
        assertThat(n.getDeletedAt()).isNull();
        verify(notificationRepository).update(n);
    }

    @Test
    void desarchivarLaDevuelveARecibidos() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setArchivedAt(Instant.now());
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.unarchive(id, ownerId);

        assertThat(n.getArchivedAt()).isNull();
    }

    @Test
    void laPapeleraEsUnBorradoLogicoReversible() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.moveToTrash(id, ownerId);
        assertThat(n.getDeletedAt()).isNotNull();

        useCase.restore(id, ownerId);
        assertThat(n.getDeletedAt()).isNull();
        verify(notificationRepository, times(2)).update(n);
    }

    @Test
    void elBorradoDefinitivoCompruebaLaPropiedadAntesDeTocarLaBaseDeDatos() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.getById(id)).thenReturn(propia(id));

        useCase.deletePermanently(id, ownerId);

        verify(notificationRepository).delete(id);
    }

    @Test
    void nadiePuedeTocarElBuzonDeOtroAunqueConozcaElIdentificador() {
        // Mismo error para "no existe" y "no es tuya": responder distinto delataría qué identificadores
        // existen en los buzones ajenos.
        UUID ajena = UUID.randomUUID();
        UUID intruso = UUID.randomUUID();
        when(notificationRepository.getById(ajena)).thenReturn(propia(ajena));

        assertThatThrownBy(() -> useCase.archive(ajena, intruso)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.moveToTrash(ajena, intruso)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.deletePermanently(ajena, intruso)).isInstanceOf(NotFoundException.class);
        verify(notificationRepository, never()).update(any());
        verify(notificationRepository, never()).delete(any());
    }

    @Test
    void unaNotificacionQueNoExisteDaNoEncontradoEnLugarDeNuloEnCadena() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.restore(id, ownerId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sinUsuarioAutenticadoNoSePuedeMutarNingunaNotificacion() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.getById(id)).thenReturn(propia(id));

        assertThatThrownBy(() -> useCase.unarchive(id, null)).isInstanceOf(NotFoundException.class);
    }

    /* ==================== acuse de recibo y estados ==================== */

    @Test
    void abrirlaPorPrimeraVezLaPasaDeNuevaARecibida() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setStatus("NEW");
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.markRead(id, ownerId);

        assertThat(n.getStatus()).isEqualTo("RECEIVED");
        assertThat(n.getReadAt()).isNotNull();
    }

    @Test
    void unaNotificacionYaGestionadaNoRetrocedeAlLeerla() {
        // Si markRead reescribiera el estado, abrir una incidencia RESUELTA la devolvería a la cola.
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setStatus("RESOLVED");
        n.setReadAt(Instant.now());
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.markRead(id, ownerId);

        assertThat(n.getStatus()).isEqualTo("RESOLVED");
        verify(notificationRepository, never()).update(any());
    }

    @Test
    void cambiarDeEstadoLaDaPorLeidaAunqueNadieLaHubieraAbierto() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.setStatus(id, ownerId, Status.IN_PROGRESS);

        assertThat(n.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(n.getReadAt()).isNotNull();
        verify(notificationRepository).update(n);
    }

    @Test
    void sinEstadoIndicadoSeAsumeElAcuseDeRecibo() {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.setStatus(id, ownerId, null);

        assertThat(n.getStatus()).isEqualTo("RECEIVED");
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"NEW", "RECEIVED"})
    void losEstadosInicialesNoGeneranCorreoAlSolicitante(Status estado) {
        // El acuse de recibo ya se envía al crear la solicitud: repetirlo sería spam.
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setPayload(new HashMap<>(Map.of("email", "cliente@example.com")));
        when(notificationRepository.getById(id)).thenReturn(n);

        useCase.setStatus(id, ownerId, estado);

        verifyNoInteractions(emailQueue);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"IN_PROGRESS", "WAITING", "RESOLVED"})
    void cadaEstadoGestionableAvisaAlSolicitantePorCorreo(Status estado) {
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setPayload(new HashMap<>(Map.of("email", "cliente@example.com", "subject", "Mi pedido")));
        when(notificationRepository.getById(id)).thenReturn(n);
        when(userRepository.findByEmail("cliente@example.com")).thenReturn(Optional.empty());

        useCase.setStatus(id, ownerId, estado);

        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(emailQueue).enqueue(eq("cliente@example.com"), anyString(), eq("emails/notification"),
                vars.capture());
        assertThat(vars.getValue().get("bodyHtml").toString()).contains("Mi pedido");
    }

    @Test
    void siElSolicitanteTieneCuentaTambienRecibeElAvisoEnSuBuzon() {
        UUID id = UUID.randomUUID();
        UUID solicitante = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setPayload(new HashMap<>(Map.of("email", "Cliente@Example.com")));
        when(notificationRepository.getById(id)).thenReturn(n);
        when(userRepository.findByEmail("cliente@example.com"))
                .thenReturn(Optional.of(User.builder().id(solicitante).email("cliente@example.com").build()));

        useCase.setStatus(id, ownerId, Status.RESOLVED);

        ArgumentCaptor<PlatformNotification> creada = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(creada.capture());
        assertThat(creada.getValue().getUserId()).isEqualTo(solicitante);
        assertThat(creada.getValue().getEventType()).isEqualTo("SUPPORT_STATUS_RESOLVED");
        assertThat(creada.getValue().getChannel()).isEqualTo("IN_APP");
    }

    @Test
    void elCuerpoDelCorreoEscapaElHtmlQueVengaDelFormulario() {
        // El asunto lo escribe un desconocido: insertarlo tal cual en el HTML del correo es una inyección.
        UUID id = UUID.randomUUID();
        PlatformNotification n = propia(id);
        n.setPayload(new HashMap<>(Map.of("email", "cliente@example.com", "subject", "<script>alert(1)</script>")));
        when(notificationRepository.getById(id)).thenReturn(n);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        useCase.setStatus(id, ownerId, Status.WAITING);

        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(emailQueue).enqueue(anyString(), anyString(), anyString(), vars.capture());
        assertThat(vars.getValue().get("bodyHtml").toString()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void sinCorreoValidoEnLaSolicitudNoSeIntentaEnviarNada() {
        UUID id = UUID.randomUUID();
        PlatformNotification sinPayload = propia(id);
        PlatformNotification correoRoto = propia(id);
        correoRoto.setPayload(new HashMap<>(Map.of("email", "no-es-un-correo")));
        when(notificationRepository.getById(id)).thenReturn(sinPayload, correoRoto);

        useCase.setStatus(id, ownerId, Status.RESOLVED);
        useCase.setStatus(id, ownerId, Status.RESOLVED);

        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    /* ==================== marcar todo como leído ==================== */

    @Test
    void marcarTodoComoLeidoSoloEscribeLasQueSeguianSinLeer() {
        // Reescribir las ya leídas machacaría la fecha real de lectura y generaría escrituras inútiles.
        PlatformNotification sinLeer = PlatformNotification.builder().userId(ownerId).build();
        Instant leidaEl = Instant.now().minusSeconds(3600);
        PlatformNotification leida = PlatformNotification.builder().userId(ownerId).readAt(leidaEl).build();
        when(notificationRepository.findByUserId(ownerId)).thenReturn(List.of(sinLeer, leida));

        useCase.markAllRead(ownerId);

        assertThat(sinLeer.getReadAt()).isNotNull();
        assertThat(leida.getReadAt()).isEqualTo(leidaEl);
        verify(notificationRepository).update(sinLeer);
        verify(notificationRepository, never()).update(leida);
    }

    /* ==================== envío desde el panel de administración ==================== */

    @Test
    void elEnvioMasivoLlegaATodosLosUsuariosYDevuelveCuantosSon() {
        when(userRepository.findAll()).thenReturn(List.of(User.builder().id(UUID.randomUUID()).build(),
                User.builder().id(UUID.randomUUID()).build()));

        assertThat(useCase.sendAdminNotification("all", "Aviso", "Cuerpo")).isEqualTo(2);
        verify(notificationRepository, times(2)).save(any());
    }

    @Test
    void sinDestinatarioElEnvioSeInterpretaComoMasivo() {
        when(userRepository.findAll()).thenReturn(List.of(User.builder().id(UUID.randomUUID()).build()));

        assertThat(useCase.sendAdminNotification(null, "Aviso", "Cuerpo")).isEqualTo(1);
        assertThat(useCase.sendAdminNotification("   ", "Aviso", "Cuerpo")).isEqualTo(1);
    }

    @Test
    void elEnvioIndividualNormalizaElCorreoDelDestinatario() {
        UUID destino = UUID.randomUUID();
        when(userRepository.findByEmail("cliente@example.com"))
                .thenReturn(Optional.of(User.builder().id(destino).build()));

        assertThat(useCase.sendAdminNotification("  Cliente@Example.COM ", "Aviso", "Cuerpo")).isEqualTo(1);

        ArgumentCaptor<PlatformNotification> creada = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(creada.capture());
        assertThat(creada.getValue().getUserId()).isEqualTo(destino);
        assertThat(creada.getValue().getEventType()).isEqualTo("ADMIN_MESSAGE");
    }

    @Test
    void enviarAUnCorreoQueNoExisteEsUnErrorDeNegocioYNoUnEnvioSilencioso() {
        when(userRepository.findByEmail("nadie@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.sendAdminNotification("nadie@example.com", "Aviso", "Cuerpo"))
                .isInstanceOf(BusinessException.class);
        verify(notificationRepository, never()).save(any());
    }
}
