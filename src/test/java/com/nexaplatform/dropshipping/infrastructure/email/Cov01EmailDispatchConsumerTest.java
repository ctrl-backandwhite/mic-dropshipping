package com.nexaplatform.dropshipping.infrastructure.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterSubscriberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterSubscriberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * Reglas del puente Kafka → correo: qué eventos generan email, cuándo manda el opt-out de marketing y
 * cómo se construye la URL del botón.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov01EmailDispatchConsumerTest {

    private static final String BASE_URL = "https://tienda.test";
    private static final String TEMPLATE = "emails/notification";

    @Mock
    EmailQueueService emailQueue;
    @Mock
    UserRepository userRepository;
    @Mock
    NewsletterSubscriberRepository subscriberRepository;

    EmailDispatchConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EmailDispatchConsumer(new ObjectMapper(), emailQueue, userRepository, subscriberRepository);
        ReflectionTestUtils.setField(consumer, "baseUrl", BASE_URL);
    }

    private static ConsumerRecord<String, Object> record(String topic, Map<String, Object> payload) {
        return new ConsumerRecord<>(topic, 0, 0L, "k", payload);
    }

    private static Map<String, Object> notification(String email, String title) {
        Map<String, Object> n = new HashMap<>();
        n.put("userEmail", email);
        n.put("title", title);
        return n;
    }

    private Map<String, Object> captureVars() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(anyString(), anyString(), eq(TEMPLATE), captor.capture());
        return captor.getValue();
    }

    /* ============ onDispatch ============ */

    @Test
    void unEventoSinDestinatarioNoGeneraCorreo() {
        // El topic lleva también notificaciones internas (sin userEmail): no son correos, se ignoran.
        Map<String, Object> payload = notification(null, "Pedido enviado");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        verifyNoInteractions(emailQueue);
    }

    @Test
    void unEventoConDestinatarioEnBlancoNoGeneraCorreo() {
        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, notification("   ", "Pedido enviado")));

        verifyNoInteractions(emailQueue);
    }

    @Test
    void unEventoSinAsuntoNoGeneraCorreo() {
        // El título es el asunto del correo: sin él saldría un email con la línea de asunto vacía.
        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, notification("ana@test.com", null)));

        verifyNoInteractions(emailQueue);
    }

    @Test
    void unCorreoDeMarketingNoSeEnviaAQuienSeDioDeBaja() {
        Map<String, Object> payload = notification("ana@test.com", "Novedades");
        payload.put("marketing", true);
        UserEntity user = new UserEntity();
        user.setMarketingOptOut(true);
        when(userRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unCorreoTransaccionalSeEnviaAunqueElUsuarioRechaceElMarketing() {
        // El opt-out cubre SOLO campañas. Si silenciara los transaccionales, el cliente no recibiría ni la
        // confirmación de su pedido.
        Map<String, Object> payload = notification("ana@test.com", "Tu pedido va en camino");
        UserEntity user = new UserEntity();
        user.setMarketingOptOut(true);
        when(userRepository.findByEmail("ana@test.com")).thenReturn(Optional.of(user));

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        verify(emailQueue).enqueue(eq("ana@test.com"), eq("Tu pedido va en camino"), eq(TEMPLATE), anyMap());
    }

    @Test
    void unCorreoDeMarketingSeEnviaSiElDestinatarioNoEstaRegistrado() {
        // Un suscriptor que no es usuario de la plataforma no tiene preferencia guardada: no es una baja.
        Map<String, Object> payload = notification("nueva@test.com", "Novedades");
        payload.put("marketing", true);
        when(userRepository.findByEmail("nueva@test.com")).thenReturn(Optional.empty());

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        verify(emailQueue).enqueue(eq("nueva@test.com"), eq("Novedades"), eq(TEMPLATE), anyMap());
    }

    @Test
    void unaUrlYaAbsolutaDelBotonSeRespetaSinPrefijarla() {
        // Puede apuntar fuera del escaparate (seguimiento del transportista, pasarela de pago).
        Map<String, Object> payload = notification("ana@test.com", "Seguimiento");
        payload.put("ctaUrl", "https://tracking.carrier.test/AB123");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        assertThat(captureVars()).containsEntry("ctaUrl", "https://tracking.carrier.test/AB123");
    }

    @Test
    void unaRutaRelativaConBarraSePrefijaConLaBaseSinDuplicarla() {
        Map<String, Object> payload = notification("ana@test.com", "Pedido");
        payload.put("ctaUrl", "/orders/42");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        assertThat(captureVars()).containsEntry("ctaUrl", BASE_URL + "/orders/42");
    }

    @Test
    void unaRutaRelativaSinBarraRecibeLaBarraSeparadora() {
        // Sin este cuidado saldría "https://tienda.testorders/42", un enlace roto en todos los correos.
        Map<String, Object> payload = notification("ana@test.com", "Pedido");
        payload.put("ctaUrl", "orders/42");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        assertThat(captureVars()).containsEntry("ctaUrl", BASE_URL + "/orders/42");
    }

    @Test
    void sinUrlDeBotonNoSeInventaUnEnlaceALaBase() {
        Map<String, Object> payload = notification("ana@test.com", "Aviso");
        payload.put("ctaUrl", "  ");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        Map<String, Object> vars = captureVars();
        assertThat(vars).containsKey("ctaUrl");
        assertThat(vars.get("ctaUrl")).isNull();
    }

    @Test
    void elCuerpoYElPreheaderDelEventoLleganAlaPlantilla() {
        Map<String, Object> payload = notification("ana@test.com", "Aviso");
        payload.put("body", "Texto plano");
        payload.put("bodyHtml", "<p>Texto</p>");
        payload.put("preheader", "Resumen corto");
        payload.put("ctaLabel", "Ver pedido");

        consumer.onDispatch(record(NexaTopics.NOTIFICATIONS_DISPATCH, payload));

        assertThat(captureVars()).containsEntry("title", "Aviso").containsEntry("body", "Texto plano")
                .containsEntry("bodyHtml", "<p>Texto</p>").containsEntry("preheader", "Resumen corto")
                .containsEntry("ctaLabel", "Ver pedido");
    }

    @Test
    void unFalloAlEncolarNoTumbaAlConsumidor() {
        // Si la excepción escapara, Kafka reintentaría el mismo offset sin fin y la partición se atascaría.
        when(emailQueue.enqueue(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("smtp caído"));

        assertThatCode(() -> consumer.onDispatch(
                record(NexaTopics.NOTIFICATIONS_DISPATCH, notification("ana@test.com", "Aviso"))))
                .doesNotThrowAnyException();
    }

    /* ============ onNewsletter ============ */

    @Test
    void unBoletinSinAsuntoNoSeEnviaANadie() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bodyHtml", "<p>Hola</p>");

        consumer.onNewsletter(record(NexaTopics.NEWSLETTER_SEND, payload));

        verifyNoInteractions(subscriberRepository);
        verifyNoInteractions(emailQueue);
    }

    @Test
    void unBoletinSinCuerpoHtmlNoSeEnviaANadie() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("subject", "Boletín de julio");

        consumer.onNewsletter(record(NexaTopics.NEWSLETTER_SEND, payload));

        verifyNoInteractions(emailQueue);
    }

    @Test
    void elBoletinSaleUnaVezPorSuscriptorActivoConSuEnlaceDeBaja() {
        // El token es personal: si el enlace de baja fuera común, una baja cancelaría a todos.
        Map<String, Object> payload = new HashMap<>();
        payload.put("subject", "Boletín de julio");
        payload.put("bodyHtml", "<p>Novedades</p>");
        NewsletterSubscriberEntity a = NewsletterSubscriberEntity.builder().email("a@test.com").token("tok-a").build();
        NewsletterSubscriberEntity b = NewsletterSubscriberEntity.builder().email("b@test.com").token("tok-b").build();
        when(subscriberRepository.findByStatus("SUBSCRIBED")).thenReturn(List.of(a, b));

        consumer.onNewsletter(record(NexaTopics.NEWSLETTER_SEND, payload));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue, times(2)).enqueue(anyString(), eq("Boletín de julio"), eq(TEMPLATE), captor.capture());
        verify(emailQueue).enqueue(eq("a@test.com"), eq("Boletín de julio"), eq(TEMPLATE), anyMap());
        verify(emailQueue).enqueue(eq("b@test.com"), eq("Boletín de julio"), eq(TEMPLATE), anyMap());
        assertThat(captor.getAllValues().get(0)).containsEntry("unsubscribeUrl",
                BASE_URL + "/newsletter/unsubscribe?token=tok-a");
        assertThat(captor.getAllValues().get(1)).containsEntry("unsubscribeUrl",
                BASE_URL + "/newsletter/unsubscribe?token=tok-b");
    }

    @Test
    void elBoletinSoloVaALosSuscritosNoATodaLaTabla() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("subject", "Boletín");
        payload.put("bodyHtml", "<p>x</p>");
        when(subscriberRepository.findByStatus("SUBSCRIBED")).thenReturn(List.of());

        consumer.onNewsletter(record(NexaTopics.NEWSLETTER_SEND, payload));

        verify(subscriberRepository).findByStatus("SUBSCRIBED");
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void unFalloLeyendoLosSuscriptoresNoTumbaAlConsumidor() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("subject", "Boletín");
        payload.put("bodyHtml", "<p>x</p>");
        when(subscriberRepository.findByStatus(any())).thenThrow(new IllegalStateException("BD caída"));

        assertThatCode(() -> consumer.onNewsletter(record(NexaTopics.NEWSLETTER_SEND, payload)))
                .doesNotThrowAnyException();
    }
}
