package com.nexaplatform.dropshipping.application.notifications;

import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Clave de partición de las notificaciones.
 *
 * <p>Las notificaciones de autenticación ya toleraban que el identificador de usuario faltara y caían al
 * correo, pero las de pedido y monedero llamaban a {@code userId.toString()} a pelo. Una compra sin
 * usuario asociado —invitado, o una operación disparada por el sistema— reventaba con
 * {@code NullPointerException} justo al publicar el evento, es decir después de haber cobrado.
 */
class NotificationsPublisherKeyTest {

    private final EventPublisher events = mock(EventPublisher.class);
    private final NotificationsPublisher publisher = new NotificationsPublisher(events);

    @Test
    void sinIdentificadorDeUsuarioLaClaveEsElCorreoYNoRevienta() {
        publisher.orderPlaced(null, "invitado@example.com", "NX-1", "12,00 €", "EUR", "es");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(events).publish(anyString(), anyString(), anyString(), key.capture(), any());
        assertThat(key.getValue()).isEqualTo("invitado@example.com");
    }

    @Test
    void conIdentificadorDeUsuarioLaClaveSigueSiendoEseIdentificador() {
        UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        publisher.orderPlaced(userId, "cliente@example.com", "NX-2", "30,00 €", "EUR", "es");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(events).publish(anyString(), anyString(), anyString(), key.capture(), any());
        assertThat(key.getValue()).isEqualTo(userId.toString());
    }

    @Test
    void elMonederoTampocoSeCaeSinIdentificadorDeUsuario() {
        publisher.walletRecharged(null, "sistema@example.com", 2500L, "STRIPE", "es");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(events).publish(anyString(), anyString(), key.capture(), anyString(), any());
        assertThat(key.getValue()).isEqualTo("sistema@example.com");
    }
}
