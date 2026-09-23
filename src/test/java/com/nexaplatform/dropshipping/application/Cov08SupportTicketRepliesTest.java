package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.SupportTicketUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.model.SupportTicketReply;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.SupportTicketRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketReplyEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketReplyJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hilo de mensajes de un ticket de soporte.
 *
 * <p>Dos cosas que no se pueden romper: el ticket de un cliente no lo puede leer ni contestar otro
 * cliente —y la negativa se da como "no existe", para no confirmar que el ticket existe— y el lado del
 * mensaje (cliente o soporte) se DERIVA de quién escribe, no viaja en la petición: si viajara, cualquiera
 * podría hacerse pasar por soporte en su propio hilo.
 */
class Cov08SupportTicketRepliesTest {

    private SupportTicketRepository ticketRepository;
    private SupportTicketReplyJpaRepository replyRepository;
    private NotificationRepository notificationRepository;
    private UserRepository userRepository;
    private SupportTicketUseCaseImpl useCase;

    private final UUID ticketId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID clienteId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final UUID agenteId = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @BeforeEach
    void setUp() {
        ticketRepository = mock(SupportTicketRepository.class);
        replyRepository = mock(SupportTicketReplyJpaRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        useCase = new SupportTicketUseCaseImpl(ticketRepository, replyRepository, notificationRepository,
                userRepository);
        when(replyRepository.save(any(SupportTicketReplyEntity.class))).thenAnswer(i -> {
            SupportTicketReplyEntity e = i.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    private SupportTicket ticketExistente(String status) {
        SupportTicket ticket = SupportTicket.builder().id(ticketId).userId(clienteId).subject("No me llega")
                .status(status).build();
        when(ticketRepository.getById(ticketId)).thenReturn(ticket);
        return ticket;
    }

    private static SupportTicketReplyEntity mensaje(UUID authorId, String body) {
        SupportTicketReplyEntity e = SupportTicketReplyEntity.builder().ticketId(UUID.randomUUID()).authorId(authorId)
                .body(body).createdAt(Instant.now()).build();
        e.setId(UUID.randomUUID());
        return e;
    }

    // ─────────────────────── permisos ───────────────────────

    @Test
    void otroClienteNoPuedeLeerElHiloYRecibeUnNoEncontrado() {
        ticketExistente("OPEN");
        UUID intruso = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.listReplies(ticketId, intruso, false)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void otroClienteTampocoPuedeEscribirEnElHilo() {
        ticketExistente("OPEN");
        UUID intruso = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.addReply(ticketId, intruso, false, "hola"))
                .isInstanceOf(NotFoundException.class);
        verify(replyRepository, never()).save(any());
    }

    @Test
    void unAdministradorPuedeLeerCualquierHilo() {
        ticketExistente("OPEN");
        when(replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(mensaje(clienteId, "No me llega el pedido")));

        List<SupportTicketReply> hilo = useCase.listReplies(ticketId, agenteId, true);

        assertThat(hilo).hasSize(1);
    }

    @Test
    void unTicketQueNoExisteDaNoEncontradoTantoAlLeerComoAlEscribir() {
        when(ticketRepository.getById(ticketId)).thenReturn(null);

        assertThatThrownBy(() -> useCase.listReplies(ticketId, clienteId, true)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.addReply(ticketId, clienteId, true, "hola"))
                .isInstanceOf(NotFoundException.class);
    }

    // ─────────────────────── lado del mensaje ───────────────────────

    @Test
    void elLadoDelMensajeSeDeduceDeQuienEscribeYNoDeLaPeticion() {
        ticketExistente("OPEN");
        when(replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId))
                .thenReturn(List.of(mensaje(clienteId, "No me llega"), mensaje(agenteId, "Lo revisamos")));

        List<SupportTicketReply> hilo = useCase.listReplies(ticketId, clienteId, false);

        assertThat(hilo).extracting(SupportTicketReply::fromSupport).containsExactly(false, true);
    }

    @Test
    void elMensajeDelDuenoNuncaSeMarcaComoDeSoporteAunqueSeaAdministrador() {
        // Un admin que abre su propio ticket sigue siendo el cliente de ese hilo.
        ticketExistente("OPEN");

        SupportTicketReply guardado = useCase.addReply(ticketId, clienteId, true, "sigo esperando");

        assertThat(guardado.fromSupport()).isFalse();
    }

    // ─────────────────────── contenido ───────────────────────

    @Test
    void unMensajeVacioSeRechaza() {
        assertThatThrownBy(() -> useCase.addReply(ticketId, clienteId, false, "   "))
                .isInstanceOf(BusinessException.class);
        verify(replyRepository, never()).save(any());
    }

    @Test
    void unMensajeAusenteSeRechaza() {
        assertThatThrownBy(() -> useCase.addReply(ticketId, clienteId, false, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void elMensajeSeGuardaSinEspaciosSobrantes() {
        ticketExistente("OPEN");

        SupportTicketReply guardado = useCase.addReply(ticketId, clienteId, false, "  hola  ");

        assertThat(guardado.body()).isEqualTo("hola");
    }

    // ─────────────────────── reapertura ───────────────────────

    @Test
    void siElClienteEscribeEnUnTicketResueltoElTicketSeReabre() {
        // Sin esto, la respuesta del cliente caería en un ticket cerrado que nadie vuelve a mirar.
        SupportTicket ticket = ticketExistente("RESOLVED");

        useCase.addReply(ticketId, clienteId, false, "sigue sin llegar");

        assertThat(ticket.getStatus()).isEqualTo("OPEN");
        verify(ticketRepository).update(ticket);
    }

    @Test
    void siEsSoporteQuienEscribeElTicketResueltoSigueResuelto() {
        SupportTicket ticket = ticketExistente("RESOLVED");

        useCase.addReply(ticketId, agenteId, true, "te confirmamos el reembolso");

        assertThat(ticket.getStatus()).isEqualTo("RESOLVED");
        verify(ticketRepository, never()).update(any());
    }

    // ─────────────────────── avisos ───────────────────────

    @Test
    void laRespuestaDeSoporteAvisaAlDuenoDelTicket() {
        ticketExistente("OPEN");

        useCase.addReply(ticketId, agenteId, true, "Ya está en camino");

        ArgumentCaptor<PlatformNotification> aviso = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(aviso.capture());
        assertThat(aviso.getValue().getUserId()).isEqualTo(clienteId);
        assertThat(aviso.getValue().getEventType()).isEqualTo("SUPPORT_REPLY");
        assertThat(aviso.getValue().getTitle()).contains("No me llega");
    }

    @Test
    void elMensajeDelClienteAvisaATodosLosAdministradores() {
        ticketExistente("OPEN");
        when(userRepository.findAll())
                .thenReturn(List.of(User.builder().id(UUID.randomUUID()).role(UserRole.ADMIN).build(),
                        User.builder().id(UUID.randomUUID()).role(UserRole.ADMIN).build(),
                        User.builder().id(clienteId).role(UserRole.USER).build()));

        useCase.addReply(ticketId, clienteId, false, "sigo esperando");

        verify(notificationRepository, times(2)).save(any(PlatformNotification.class));
    }

    @Test
    void elAvisoLlevaSoloUnExtractoDelMensaje() {
        // El aviso es un resumen: un mensaje de miles de caracteres no cabe en la bandeja del panel.
        ticketExistente("OPEN");

        useCase.addReply(ticketId, agenteId, true, "z".repeat(500));

        ArgumentCaptor<PlatformNotification> aviso = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(aviso.capture());
        assertThat(aviso.getValue().getBody()).hasSize(121).endsWith("…");
    }

    // ─────────────────────── listados ───────────────────────

    @Test
    void elListadoDeAdminSinFiltroTraeTodosLosTickets() {
        useCase.adminList(null);
        useCase.adminList("   ");

        verify(ticketRepository, times(2)).findAll();
        verify(ticketRepository, never()).findByStatus(any());
    }

    @Test
    void elListadoDeAdminConFiltroBuscaPorEstado() {
        useCase.adminList("OPEN");

        verify(ticketRepository).findByStatus("OPEN");
        verify(ticketRepository, never()).findAll();
    }

    @Test
    void misTicketsSoloConsultaLosDelUsuario() {
        useCase.myTickets(clienteId);

        verify(ticketRepository).findByUserId(clienteId);
    }

    @Test
    void unTicketConPrioridadIndicadaLaConserva() {
        // El valor por defecto solo debe aplicarse cuando NO viene: si sobrescribiera, una urgencia
        // marcada por el agente se degradaría a normal.
        SupportTicket entrante = SupportTicket.builder().subject("Caída total").priority("HIGH").build();
        when(ticketRepository.save(entrante)).thenReturn(entrante);

        useCase.open(clienteId, entrante);

        assertThat(entrante.getPriority()).isEqualTo("HIGH");
        assertThat(entrante.getStatus()).isEqualTo("OPEN");
    }
}
