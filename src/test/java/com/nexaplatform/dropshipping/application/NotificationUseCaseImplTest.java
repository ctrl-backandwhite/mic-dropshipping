package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.NotificationUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.UnreadCount;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationUseCaseImplTest {

    @Mock
    NotificationRepository notificationRepository;
    @InjectMocks
    NotificationUseCaseImpl useCase;

    @Test
    void unreadCount_wrapsRepositoryCounter() {
        UUID userId = UUID.randomUUID();
        when(notificationRepository.countUnreadByUserId(userId)).thenReturn(7L);

        UnreadCount result = useCase.unreadCount(userId);

        assertThat(result.getCount()).isEqualTo(7L);
    }

    @Test
    void markRead_setsReadAtWhenUnread() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        PlatformNotification model = PlatformNotification.builder().id(id).userId(userId).build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, userId);

        assertThat(model.getReadAt()).isNotNull();
        verify(notificationRepository).update(model);
    }

    @Test
    void markRead_isNoOpWhenAlreadyRead() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // Estado coherente (leída + RECEIVED): markRead no debe cambiar nada.
        PlatformNotification model = PlatformNotification.builder().id(id).userId(userId).readAt(Instant.now())
                .status("RECEIVED").build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, userId);

        verify(notificationRepository, never()).update(model);
    }

    @Test
    void markRead_isNoOpForOtherUsersNotification() {
        UUID id = UUID.randomUUID();
        PlatformNotification model = PlatformNotification.builder().id(id).userId(UUID.randomUUID()).build();
        when(notificationRepository.getById(id)).thenReturn(model);

        useCase.markRead(id, UUID.randomUUID()); // otro usuario → no-op (IDOR)

        verify(notificationRepository, never()).update(model);
    }

    /**
     * Quien pagaba desde la aplicación abría «Avisos» y lo encontraba VACÍO, con el pedido ya
     * cobrado: el pago solo salía por correo y por el bus, y el buzón de la app no se enteraba.
     */
    @Test
    void orderPaid_dejaElAvisoEnElBuzonEnElIdiomaDelComprador() {
        UUID userId = UUID.randomUUID();

        useCase.orderPaid(userId, "NX-1788944263-4955", "es");

        ArgumentCaptor<PlatformNotification> captor = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(captor.capture());
        PlatformNotification aviso = captor.getValue();
        assertThat(aviso.getUserId()).isEqualTo(userId);
        assertThat(aviso.getEventType()).isEqualTo("ORDER_PAID");
        assertThat(aviso.getChannel()).isEqualTo("IN_APP");
        assertThat(aviso.getTitle()).isEqualTo("Pago confirmado");
        assertThat(aviso.getBody()).contains("NX-1788944263-4955");
    }

    @Test
    void orderPaid_traduceAlIdiomaConElQueNavega() {
        useCase.orderPaid(UUID.randomUUID(), "NX-1", "de");

        ArgumentCaptor<PlatformNotification> captor = ArgumentCaptor.forClass(PlatformNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Zahlung bestätigt");
    }

    /** Sin destinatario o sin pedido no hay nada que dejar: no se guarda una fila vacía. */
    @Test
    void orderPaid_noGuardaNadaSinDatos() {
        useCase.orderPaid(null, "NX-1", "es");
        useCase.orderPaid(UUID.randomUUID(), "  ", "es");

        verify(notificationRepository, never()).save(any());
    }
}
