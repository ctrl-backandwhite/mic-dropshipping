package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.OdmProjectCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.OdmStatusUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodAiGenerateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodDesignCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ShippingCalculatorDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketResolveDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SupportReplyDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.mapper.NotificationDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.OdmProjectDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.PodDesignDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.ShippingDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.SupportTicketDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.WarehouseDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.application.usecase.OdmProjectUseCase;
import com.nexaplatform.dropshipping.application.usecase.PodDesignUseCase;
import com.nexaplatform.dropshipping.application.usecase.ShippingUseCase;
import com.nexaplatform.dropshipping.application.usecase.SupportTicketUseCase;
import com.nexaplatform.dropshipping.application.usecase.WarehouseUseCase;
import com.nexaplatform.dropshipping.domain.model.SupportTicketReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del controlador de extras de plataforma: la identidad SIEMPRE sale del token (nunca del
 * cuerpo), la carpeta de notificaciones tolera basura pero el estado no, y el flag de "escrito por
 * soporte" lo decide el endpoint (no el cliente).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07PlatformExtrasControllerTest {

    @Mock
    PodDesignDtoMapper podMapper;
    @Mock
    PodDesignUseCase podUseCase;
    @Mock
    OdmProjectDtoMapper odmMapper;
    @Mock
    OdmProjectUseCase odmUseCase;
    @Mock
    SupportTicketDtoMapper ticketMapper;
    @Mock
    SupportTicketUseCase ticketUseCase;
    @Mock
    NotificationDtoMapper notificationMapper;
    @Mock
    NotificationUseCase notificationUseCase;
    @Mock
    WarehouseDtoMapper warehouseMapper;
    @Mock
    WarehouseUseCase warehouseUseCase;
    @Mock
    ShippingDtoMapper shippingMapper;
    @Mock
    ShippingUseCase shippingUseCase;

    @InjectMocks
    PlatformExtrasController controller;

    private Authentication auth;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
    }

    /* ==================== POD ==================== */

    @Test
    void crearUnDisenoLoAtribuyeAlUsuarioDelToken() {
        PodDesignCreateDtoIn req = new PodDesignCreateDtoIn();

        controller.createDesign(auth, req);

        verify(podUseCase).create(eq(userId), any());
    }

    @Test
    void renombrarYBorrarUnDisenoPasanElUsuarioDelToken() {
        UUID id = UUID.randomUUID();

        controller.renameDesign(auth, id, "Nuevo");
        ResponseEntity<Void> deleted = controller.deleteDesign(auth, id);

        verify(podUseCase).renameDesign(userId, id, "Nuevo");
        verify(podUseCase).deleteDesign(userId, id);
        // El borrado responde 204 sin cuerpo.
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
        assertThat(deleted.getBody()).isNull();
    }

    @Test
    void losProductosBaseYLosDisenosPropiosSeConsultanConElIdiomaYElUsuario() {
        controller.podBlanks("es");
        controller.myDesigns(auth);

        verify(podUseCase).blanks("es");
        verify(podUseCase).myDesigns(userId);
    }

    @Test
    void laGeneracionPorIaSoloPasaElPrompt() {
        PodAiGenerateDtoIn req = new PodAiGenerateDtoIn();
        req.setPrompt("un gato astronauta");

        controller.aiGenerate(req);

        verify(podUseCase).aiGenerate("un gato astronauta");
    }

    /* ==================== ODM ==================== */

    @Test
    void losProyectosOdmDelUsuarioSeFiltranPorSuIdDelToken() {
        UUID id = UUID.randomUUID();
        OdmProjectCreateDtoIn req = new OdmProjectCreateDtoIn();

        controller.createOdm(auth, req);
        controller.myOdm(auth);
        controller.getOdm(auth, id);
        controller.updateOdm(auth, id, req);
        ResponseEntity<Void> deleted = controller.deleteOdm(auth, id);

        verify(odmUseCase).myProjects(userId);
        verify(odmUseCase).getById(userId, id);
        verify(odmUseCase).delete(userId, id);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void elListadoYElCambioDeEstadoDeAdminNoLlevanUsuario() {
        UUID id = UUID.randomUUID();
        OdmStatusUpdateDtoIn req = new OdmStatusUpdateDtoIn();
        req.setStatus("APPROVED");

        controller.adminOdm("PENDING");
        controller.setOdmStatus(id, req);

        verify(odmUseCase).adminList("PENDING");
        verify(odmUseCase).setStatus(id, "APPROVED");
    }

    /* ==================== Tickets ==================== */

    @Test
    void unTicketSeAbreSiempreANombreDelUsuarioDelToken() {
        SupportTicketCreateDtoIn req = new SupportTicketCreateDtoIn();

        controller.openTicket(auth, req);
        controller.myTickets(auth);

        verify(ticketUseCase).open(eq(userId), any());
        verify(ticketUseCase).myTickets(userId);
    }

    @Test
    void elHiloDelClienteSeLeeYSeEscribeSinPrivilegiosDeSoporte() {
        UUID id = UUID.randomUUID();
        when(ticketUseCase.listReplies(id, userId, false)).thenReturn(List.of(reply(false, "hola")));
        when(ticketUseCase.addReply(id, userId, false, "gracias")).thenReturn(reply(false, "gracias"));

        List<SupportReplyDtoOut> replies = controller.myTicketReplies(auth, id);
        SupportReplyDtoOut added = controller.myTicketReply(auth, id, Map.of("body", "gracias"));

        // El "false" lo pone el endpoint: si viniera del cliente, cualquiera podría firmar como soporte.
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).isFromSupport()).isFalse();
        assertThat(replies.get(0).getBody()).isEqualTo("hola");
        assertThat(added.isFromSupport()).isFalse();
    }

    @Test
    void elHiloDeAdminSeLeeSinDuenoYSeEscribeComoSoporte() {
        UUID id = UUID.randomUUID();
        when(ticketUseCase.listReplies(id, null, true)).thenReturn(List.of(reply(true, "te ayudamos")));
        when(ticketUseCase.addReply(id, userId, true, "resuelto")).thenReturn(reply(true, "resuelto"));

        List<SupportReplyDtoOut> replies = controller.adminTicketReplies(id);
        SupportReplyDtoOut added = controller.adminTicketReply(auth, id, Map.of("body", "resuelto"));

        assertThat(replies.get(0).isFromSupport()).isTrue();
        assertThat(added.isFromSupport()).isTrue();
        assertThat(added.getBody()).isEqualTo("resuelto");
    }

    @Test
    void resolverUnTicketEsUnaOperacionDeAdminSobreElIdDelTicket() {
        UUID id = UUID.randomUUID();
        SupportTicketResolveDtoIn req = new SupportTicketResolveDtoIn();
        req.setResolution("Reembolsado");

        controller.adminTickets("OPEN");
        controller.resolve(id, req);

        verify(ticketUseCase).adminList("OPEN");
        verify(ticketUseCase).resolve(id, "Reembolsado");
    }

    /* ==================== Notificaciones ==================== */

    @ParameterizedTest
    @ValueSource(strings = { "archived", " TRASH ", "inbox" })
    void laCarpetaDeNotificacionesAceptaMayusculasMinusculasYEspacios(String folder) {
        controller.notifications(auth, folder);

        verify(notificationUseCase).myNotifications(userId,
                NotificationUseCase.Folder.valueOf(folder.trim().toUpperCase()));
    }

    @Test
    void unaCarpetaDesconocidaCaeEnLaBandejaDeEntradaEnVezDeFallar() {
        controller.notifications(auth, "carpeta-inventada");
        controller.notifications(auth, null);

        // Un enlace viejo o un parámetro basura no puede tumbar la bandeja del usuario.
        verify(notificationUseCase, times(2))
                .myNotifications(userId, NotificationUseCase.Folder.INBOX);
    }

    @Test
    void unEstadoDeNotificacionDesconocidoSiSeRechaza() {
        UUID id = UUID.randomUUID();

        // A diferencia de la carpeta, el estado es una ESCRITURA: guardar uno inválido corrompería el dato.
        assertThatThrownBy(() -> controller.setNotificationStatus(auth, id, "inventado"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unEstadoNuloSeInterpretaComoRecibido() {
        UUID id = UUID.randomUUID();

        controller.setNotificationStatus(auth, id, null);
        controller.setNotificationStatus(auth, id, " in_progress ");

        verify(notificationUseCase).setStatus(id, userId, NotificationUseCase.Status.RECEIVED);
        verify(notificationUseCase).setStatus(id, userId, NotificationUseCase.Status.IN_PROGRESS);
    }

    @Test
    void todasLasAccionesSobreUnaNotificacionExigenSerSuDueno() {
        UUID id = UUID.randomUUID();

        controller.archiveNotification(auth, id);
        controller.unarchiveNotification(auth, id);
        controller.trashNotification(auth, id);
        controller.restoreNotification(auth, id);
        controller.deleteNotificationPermanently(auth, id);
        controller.markRead(auth, id);
        controller.markAllRead(auth);
        controller.unreadCount(auth);

        verify(notificationUseCase).archive(id, userId);
        verify(notificationUseCase).unarchive(id, userId);
        verify(notificationUseCase).moveToTrash(id, userId);
        verify(notificationUseCase).restore(id, userId);
        verify(notificationUseCase).deletePermanently(id, userId);
        verify(notificationUseCase).markRead(id, userId);
        verify(notificationUseCase).markAllRead(userId);
        verify(notificationUseCase).unreadCount(userId);
    }

    /* ==================== Almacenes y calculadora ==================== */

    @Test
    void losAlmacenesYSuStockSeListanPorSuIdentificador() {
        UUID id = UUID.randomUUID();

        controller.warehouses();
        controller.stockPerWarehouse(id);

        verify(warehouseUseCase).listActive();
        verify(warehouseUseCase).stockPerWarehouse(id);
    }

    @Test
    void laCalculadoraAplicaLosValoresPorDefectoDelDto() {
        ShippingCalculatorDtoIn req = new ShippingCalculatorDtoIn();

        controller.shippingCalculator(req);
        controller.carbonFootprint(req);

        // Sin peso ni cantidad se asume 500 g y 1 unidad: la calculadora nunca cotiza con ceros.
        verify(shippingUseCase).calculate(500, 1);
        verify(shippingUseCase).carbonFootprint(500, 1);
    }

    @Test
    void laCantidadExplicitaMandaSobreElCampoAntiguo() {
        ShippingCalculatorDtoIn req = new ShippingCalculatorDtoIn();
        req.setWeightGrams(1200);
        req.setQty(9);
        req.setQuantity(3);

        controller.shippingCalculator(req);

        verify(shippingUseCase).calculate(1200, 3);
    }

    /* ==================== helpers ==================== */

    private static SupportTicketReply reply(boolean fromSupport, String body) {
        return new SupportTicketReply(UUID.randomUUID(), UUID.randomUUID(), fromSupport, body, Instant.EPOCH);
    }
}
