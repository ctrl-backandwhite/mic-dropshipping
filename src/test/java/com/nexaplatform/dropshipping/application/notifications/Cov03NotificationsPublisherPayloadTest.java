package com.nexaplatform.dropshipping.application.notifications;

import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Contrato del evento que consume el microservicio de notificaciones.
 *
 * <p>El consumidor renderiza la notificación SIN volver a llamar a este backend: todo lo que necesita
 * (qué pasó, para quién, en qué idioma) tiene que viajar dentro del evento. Quitar un campo del mapa no
 * rompe ninguna compilación aquí, pero deja al usuario sin correo o con el correo en el idioma que no es.
 */
class Cov03NotificationsPublisherPayloadTest {

    private EventPublisher events;
    private NotificationsPublisher publisher;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void preparaPublicador() {
        events = mock(EventPublisher.class);
        publisher = new NotificationsPublisher(events);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cuerpoPublicadoEn(String topic) {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(topic), anyString(), any(), any(), body.capture());
        return (Map<String, Object>) body.getValue();
    }

    @Test
    void todoEventoLlevaQuePasoParaQuienYEnQueIdioma() {
        publisher.orderDelivered(userId, "cliente@example.com", "NX-9", "en");

        Map<String, Object> body = cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_ORDER_DELIVERED);
        assertThat(body).containsEntry("kind", "ORDER_DELIVERED")
                .containsEntry("userId", userId.toString())
                .containsEntry("userEmail", "cliente@example.com")
                .containsEntry("locale", "en")
                .containsEntry("source", "nx036-dropshipping")
                .containsEntry("orderNumber", "NX-9")
                .containsKey("emittedAt");
    }

    @Test
    void sinIdiomaIndicadoLaNotificacionSaleEnCastellano() {
        // Sin este valor por defecto el consumidor recibiría null y no sabría qué plantilla usar.
        publisher.orderDelivered(userId, "cliente@example.com", "NX-9", null);

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_ORDER_DELIVERED)).containsEntry("locale", "es");
    }

    @Test
    void elPedidoRealizadoViajaConSuTotalYaFormateadoYSuDivisa() {
        // El consumidor no sabe convertir ni formatear: si no le llega el texto, el correo dice "null €".
        publisher.orderPlaced(userId, "cliente@example.com", "NX-1", "85,81 €", "EUR", "es");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_ORDER_PLACED))
                .containsEntry("orderNumber", "NX-1")
                .containsEntry("totalDisplay", "85,81 €")
                .containsEntry("currency", "EUR");
    }

    @Test
    void elEnvioViajaConTransportistaYNumeroDeSeguimiento() {
        publisher.orderShipped(userId, "cliente@example.com", "NX-2", "YunExpress", "YT262110", "es");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_ORDER_SHIPPED))
                .containsEntry("carrier", "YunExpress")
                .containsEntry("trackingNumber", "YT262110");
    }

    @Test
    void losMovimientosDeMonederoViajanEnCentimosCanonicos() {
        // El importe se manda en céntimos USD (la unidad del libro mayor) para que el consumidor no tenga
        // que interpretar un decimal con separadores de otro idioma.
        publisher.walletRecharged(userId, "cliente@example.com", 2500L, "STRIPE", "es");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_WALLET_RECHARGED))
                .containsEntry("amountUsdCents", 2500L)
                .containsEntry("method", "STRIPE");
    }

    @Test
    void elCobroDelMonederoDiceContraQuePedidoVa() {
        publisher.walletCharged(userId, "cliente@example.com", 999L, "NX-3", "es");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_WALLET_CHARGED))
                .containsEntry("amountUsdCents", 999L)
                .containsEntry("orderNumber", "NX-3");
    }

    @Test
    void losEventosDeAutenticacionAdmitenDatosExtraSinNuevosTopics() {
        publisher.authEvent(userId, "cliente@example.com", "PASSWORD_RESET", Map.of("ip", "10.0.0.1"), "fr");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_AUTH))
                .containsEntry("kind", "PASSWORD_RESET")
                .containsEntry("ip", "10.0.0.1")
                .containsEntry("locale", "fr");
    }

    @Test
    void elEventoGenericoTambienAdmiteDatosExtraONinguno() {
        publisher.dispatch("CUSTOM_HOOK", userId, "cliente@example.com", null, "es");

        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_DISPATCH)).containsEntry("kind", "CUSTOM_HOOK");
    }

    @Test
    void sinIdentificadorDeUsuarioLaAutenticacionSeIdentificaPorCorreo() {
        // Compra de invitado u operación disparada por el sistema: antes reventaba con NullPointerException
        // justo al publicar, es decir después de haber cobrado.
        publisher.dispatch("CUSTOM_HOOK", null, "invitado@example.com", null, "es");

        ArgumentCaptor<String> agregado = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> particion = ArgumentCaptor.forClass(String.class);
        verify(events).publish(eq(NexaTopics.NOTIFICATIONS_DISPATCH), anyString(), agregado.capture(),
                particion.capture(), any());
        assertThat(agregado.getValue()).isEqualTo("invitado@example.com");
        assertThat(particion.getValue()).isEqualTo("invitado@example.com");
        assertThat(cuerpoPublicadoEn(NexaTopics.NOTIFICATIONS_DISPATCH)).containsEntry("userId", null);
    }

    @Test
    void cadaFamiliaDeEventosVaASuPropioTopicYConSuTipoDeAgregado() {
        // El consumidor se suscribe por topic: publicar en el que no es equivale a no publicar.
        publisher.orderPlaced(userId, "c@x.com", "NX-1", "1 €", "EUR", "es");

        verify(events).publish(eq(NexaTopics.NOTIFICATIONS_ORDER_PLACED), eq("Order"), eq("NX-1"),
                eq(userId.toString()), any());
    }
}
