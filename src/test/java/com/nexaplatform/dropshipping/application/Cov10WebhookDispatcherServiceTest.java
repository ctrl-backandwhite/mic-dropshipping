package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexaplatform.dropshipping.application.service.PartnerWebhookDispatcherService;
import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reparto de eventos a los webhooks del cliente: filtro por tipo de evento, firma HMAC del cuerpo que se
 * envía de verdad, y la máquina de reintentos (backoff y abandono). Un fallo aquí es un integrador que
 * deja de enterarse de sus pedidos, o que recibe eventos que no puede verificar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov10WebhookDispatcherServiceTest {

    @BeforeAll
    static void permitirDestinosLocales() {
        // Estos casos llaman a un servidor de pruebas en 127.0.0.1, que la protección anti-SSRF rechaza
        // por diseño. Se abre aquí y se cierra al terminar, para no dejarlo abierto a otros tests.
        PublicHttpUrl.allowPrivateTargets(true);
    }

    @AfterAll
    static void restaurarProteccion() {
        PublicHttpUrl.allowPrivateTargets(false);
    }

    @Mock
    WebhookSubscriptionRepository subscriptionRepository;
    @Mock
    WebhookDeliveryRepository deliveryRepository;
    @Mock
    PartnerWebhookDispatcherService partnerWebhooks;

    @InjectMocks
    WebhookDispatcherService service;
    WebhookDispatcherService self;

    private HttpServer server;
    private final List<Map<String, String>> recibidas = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        // `self` es la autoreferencia por el proxy (@Autowired @Lazy): sin ella queue() cae al catch y el
        // envío nunca se dispara. Se sustituye por un doble para poder observar la llamada.
        self = mock(WebhookDispatcherService.class);
        Field f = WebhookDispatcherService.class.getDeclaredField("self");
        f.setAccessible(true);
        f.set(service, self);
        when(deliveryRepository.save(any())).thenAnswer(inv -> {
            WebhookDeliveryEntity d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(UUID.randomUUID());
            }
            return d;
        });
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /* ---------- filtro por tipo de evento ---------- */

    @Test
    void unaSuscripcionQueNoEscuchaEseEventoNoRecibeNada() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("order.paid"))));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unaSuscripcionSinFiltroRecibeTodosLosEventos() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of())));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        verify(deliveryRepository).save(any());
    }

    @Test
    void elComodinRecibeCualquierEvento() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("*"))));

        service.publish("cualquier.cosa", "evt-1", Map.of());

        verify(deliveryRepository).save(any());
    }

    /* ---------- sobre y firma ---------- */

    @Test
    void laFirmaCubreExactamenteElCuerpoQueSeVaAEnviar() throws Exception {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("order.created"))));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        WebhookDeliveryEntity d = entregaGuardada();
        String body = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(d.getPayload());
        // Si la firma se calculara sobre otra cosa (otro orden, otro sobre), el integrador la rechazaría.
        assertThat(d.getSignature()).isEqualTo(WebhookDispatcherService.sign(body, "s3cr3t"));
    }

    @Test
    void cadaSuscriptorRecibeElMismoEventoFirmadoConSuPropioSecreto() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/a", "secreto-a", List.of("*")),
                        suscripcion("http://127.0.0.1:1/b", "secreto-b", List.of("*"))));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        ArgumentCaptor<WebhookDeliveryEntity> captor = ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(deliveryRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getSignature())
                .isNotEqualTo(captor.getAllValues().get(1).getSignature());
    }

    @Test
    void elSobreLlevaIdTipoFechaYDatosDelEvento() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("*"))));

        service.publish("order.created", "evt-1", Map.of("orderId", "1"));

        WebhookDeliveryEntity d = entregaGuardada();
        assertThat(d.getPayload()).containsEntry("id", "evt-1").containsEntry("type", "order.created")
                .containsEntry("data", Map.of("orderId", "1")).containsKey("createdAt");
        assertThat(d.getEventType()).isEqualTo("order.created");
        assertThat(d.getEventId()).isEqualTo("evt-1");
        assertThat(d.getTargetUrl()).isEqualTo("http://127.0.0.1:1/hook");
    }

    @Test
    void laEntregaNacePendienteYSeIntentaDeInmediatoPorElProxy() {
        when(subscriptionRepository.findByActiveTrue())
                .thenReturn(List.of(suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("*"))));

        service.publish("order.created", "evt-1", Map.of());

        WebhookDeliveryEntity d = entregaGuardada();
        assertThat(d.getStatus()).isEqualTo("PENDING");
        assertThat(d.getAttempt()).isZero();
        assertThat(d.getNextRetryAt()).isNotNull();
        // Sin pasar por el proxy el envío sería síncrono y bloquearía a quien publica el evento.
        verify(self).attempt(d.getId());
    }

    @Test
    void unaSuscripcionRotaNoImpideElRepartoALasDemas() {
        WebhookSubscriptionEntity rota = suscripcion("http://127.0.0.1:1/a", null, List.of("*"));
        WebhookSubscriptionEntity sana = suscripcion("http://127.0.0.1:1/b", "secreto-b", List.of("*"));
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of(rota, sana));

        service.publish("order.created", "evt-1", Map.of());

        // La que no se puede firmar (sin secreto) se descarta; la sana sigue recibiendo su copia.
        ArgumentCaptor<WebhookDeliveryEntity> captor = ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(deliveryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getTargetUrl()).isEqualTo("http://127.0.0.1:1/b");
    }

    @Test
    void elPingDePruebaViajaComoUnEventoMasConElIdDeLaSuscripcion() {
        UUID subId = UUID.randomUUID();
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of());

        service.publishTest(subId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(partnerWebhooks).publish(eq("test.ping"), anyString(), captor.capture());
        assertThat(captor.getValue()).containsEntry("subscriptionId", subId.toString());
    }

    @Test
    void elPingDePruebaConLaEntidadUsaSuIdentificador() {
        WebhookSubscriptionEntity s = suscripcion("http://127.0.0.1:1/hook", "s3cr3t", List.of("*"));
        when(subscriptionRepository.findByActiveTrue()).thenReturn(List.of());

        service.publishTest(s);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(partnerWebhooks).publish(eq("test.ping"), anyString(), captor.capture());
        assertThat(captor.getValue()).containsEntry("subscriptionId", s.getId().toString());
    }

    /* ---------- intento de entrega ---------- */

    @Test
    void unaEntregaQueYaTriunfoNoSeVuelveAEnviar() {
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = entrega(0, "http://127.0.0.1:1/hook");
        d.setStatus("SUCCESS");
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));

        service.attempt(id);

        // Reenviarla duplicaría el evento en el sistema del integrador.
        assertThat(d.getAttempt()).isZero();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unaEntregaInexistenteNoRevienta() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.empty());

        service.attempt(id);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unaRespuesta2xxCierraLaEntregaYCortaLosReintentos() throws Exception {
        String url = servidor(200, "ok");
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = entrega(0, url);
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));

        service.attempt(id);

        assertThat(d.getStatus()).isEqualTo("SUCCESS");
        assertThat(d.getResponseStatus()).isEqualTo(200);
        assertThat(d.getResponseBody()).isEqualTo("ok");
        assertThat(d.getNextRetryAt()).isNull();
        assertThat(d.getLastAttemptAt()).isNotNull();
        Map<String, String> headers = recibidas.get(0);
        assertThat(headers).containsEntry("X-nx036-signature", "sig").containsEntry("X-nx036-event", "order.created")
                .containsEntry("X-nx036-event-id", "evt-1").containsEntry("X-nx036-attempt", "1");
    }

    @Test
    void elCuerpoDeRespuestaSeRecortaParaNoLlenarLaTablaDeEntregas() throws Exception {
        String url = servidor(200, "x".repeat(5000));
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = entrega(0, url);
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));

        service.attempt(id);

        assertThat(d.getResponseBody()).hasSize(2000);
    }

    @Test
    void unErrorDelServidorProgramaElSiguienteReintento() throws Exception {
        String url = servidor(500, "boom");
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = entrega(0, url);
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));

        service.attempt(id);

        assertThat(d.getStatus()).isEqualTo("RETRY");
        assertThat(d.getResponseStatus()).isEqualTo(500);
        assertThat(d.getNextRetryAt()).isBetween(Instant.now().plus(50, ChronoUnit.SECONDS),
                Instant.now().plus(70, ChronoUnit.SECONDS));
    }

    @Test
    void elBackoffCreceConCadaIntentoSinSalirseDeLaTabla() {
        UUID id = UUID.randomUUID();
        WebhookDeliveryEntity d = entrega(3, "http://127.0.0.1:1/hook"); // tras incrementar = 4º intento
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(d));

        service.attempt(id);

        assertThat(d.getStatus()).isEqualTo("RETRY");
        assertThat(d.getNextRetryAt()).isBetween(Instant.now().plus(7100, ChronoUnit.SECONDS),
                Instant.now().plus(7300, ChronoUnit.SECONDS)); // 2 h
    }

    /* ---------- scheduler de reintentos ---------- */

    @Test
    void sinEntregasVencidasElSchedulerNoTocaNada() {
        when(deliveryRepository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(eq("RETRY"),
                any(Instant.class))).thenReturn(List.of());

        service.retryDueDeliveries();

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void elSchedulerMarcaPendienteAntesDeReintentarParaNoDuplicarElEnvio() {
        WebhookDeliveryEntity d = entrega(1, "http://127.0.0.1:1/hook");
        d.setId(UUID.randomUUID());
        d.setStatus("RETRY");
        d.setNextRetryAt(Instant.now().minusSeconds(5));
        when(deliveryRepository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(eq("RETRY"),
                any(Instant.class))).thenReturn(List.of(d));
        when(deliveryRepository.findById(d.getId())).thenReturn(Optional.of(d));

        service.retryDueDeliveries();

        // Si dos pasadas del planificador se solapan, la marca PENDING evita que la misma entrega salga
        // dos veces. El reintento se dispara POR EL PROXY (self.attempt), no con this: con la
        // autoinvocación corría síncrono dentro de la transacción del planificador y un suscriptor lento
        // retrasaba a todos los demás vencidos.
        assertThat(d.getStatus()).isEqualTo("PENDING");
        assertThat(d.getNextRetryAt()).isNull();
    }

    /* ---------- utilidades ---------- */

    private WebhookDeliveryEntity entregaGuardada() {
        ArgumentCaptor<WebhookDeliveryEntity> captor = ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(deliveryRepository).save(captor.capture());
        return captor.getValue();
    }

    private static WebhookSubscriptionEntity suscripcion(String url, String secret, List<String> events) {
        WebhookSubscriptionEntity s = WebhookSubscriptionEntity.builder().targetUrl(url).secret(secret)
                .events(new ArrayList<>(events)).active(true).build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private static WebhookDeliveryEntity entrega(int attempt, String targetUrl) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "evt-1");
        return WebhookDeliveryEntity.builder().eventType("order.created").eventId("evt-1").payload(payload)
                .signature("sig").targetUrl(targetUrl).status("PENDING").attempt(attempt).build();
    }

    /** Levanta un receptor de webhooks real que responde lo indicado y anota las cabeceras recibidas. */
    private String servidor(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
            recibidas.add(headers);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }
}
