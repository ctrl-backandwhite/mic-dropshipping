package com.nexaplatform.dropshipping.infrastructure.messaging.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Worker que drena el outbox transaccional hacia Kafka.
 *
 * <p>Es la pieza que garantiza que un evento producido dentro de una transacción acabe publicándose
 * aunque el broker esté caído. Las reglas que no se pueden romper: un evento solo se marca como enviado
 * cuando el broker lo ha confirmado, un fallo de uno no impide publicar el resto del lote, y los
 * reintentos se espacian con techo para no martillear al broker eternamente ni reintentar para siempre.
 */
class Cov08OutboxDispatcherTest {

    private EventOutboxRepository repo;
    private KafkaTemplate<String, Object> kafka;
    /** El segundo broker: el bus de integración, que es una instalación distinta de la interna. */
    private KafkaTemplate<String, Object> busKafka;
    private ObjectProvider<KafkaTemplate<String, Object>> proveedorBus;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(EventOutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        busKafka = mock(KafkaTemplate.class);
        proveedorBus = mock(ObjectProvider.class);
        when(proveedorBus.getIfAvailable()).thenReturn(busKafka);
        dispatcher = new OutboxDispatcher(repo, kafka, proveedorBus);
    }

    /** Instante ya vencido con el que se dan de alta los eventos del outbox. */
    private static final Instant YA_VENCIDO = Instant.parse("2026-07-01T00:00:00Z");

    private static EventOutboxEntity evento(String topic, int intentosPrevios) {
        EventOutboxEntity e = EventOutboxEntity.builder().aggregateType("Order").aggregateId("NX-1").topic(topic)
                .partitionKey("NX-1").payload(Map.of("k", "v")).attempts(intentosPrevios).nextAttemptAt(YA_VENCIDO)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private void loteDelOutbox(EventOutboxEntity... eventos) {
        when(repo.claimBatch(any(Instant.class), any(Pageable.class))).thenReturn(List.of(eventos));
    }

    private void kafkaConfirma() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(sendResult()));
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, Object> sendResult() {
        return mock(SendResult.class);
    }

    private void kafkaFalla(String topic) {
        when(kafka.send(eq(topic), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker caído")));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<UUID>> idsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void sinEventosPendientesNoSeTocaNiKafkaNiLaBaseDeDatos() {
        when(repo.claimBatch(any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        dispatcher.drain();

        verifyNoInteractions(kafka);
        verify(repo, never()).markSent(anyList(), any(Instant.class));
    }

    @Test
    void unEventoSoloSeMarcaComoEnviadoCuandoElBrokerLoConfirma() {
        // El ack y el marcado van juntos: marcar antes del ack perdería el evento si el envío falla.
        EventOutboxEntity uno = evento("orders.paid", 0);
        EventOutboxEntity dos = evento("orders.shipped", 0);
        loteDelOutbox(uno, dos);
        kafkaConfirma();

        dispatcher.drain();

        ArgumentCaptor<List<UUID>> ids = idsCaptor();
        verify(repo).markSent(ids.capture(), any(Instant.class));
        assertThat(ids.getValue()).containsExactly(uno.getId(), dos.getId());
        verify(repo, never()).save(any(EventOutboxEntity.class));
    }

    @Test
    void unFalloDelBrokerProgramaOtroIntentoYDejaConstanciaDelMotivo() {
        EventOutboxEntity uno = evento("orders.paid", 0);
        loteDelOutbox(uno);
        kafkaFalla("orders.paid");

        dispatcher.drain();

        assertThat(uno.getAttempts()).isEqualTo(1);
        assertThat(uno.getStatus()).isEqualTo("PENDING");
        assertThat(uno.getLastError()).contains("broker caído");
        assertThat(uno.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofSeconds(25)))
                .isBefore(Instant.now().plus(Duration.ofSeconds(35)));
        verify(repo).save(uno);
        verify(repo, never()).markSent(anyList(), any(Instant.class));
    }

    @Test
    void laEsperaEntreIntentosCreceConCadaFalloPeroNoPasaDeUnaHora() {
        // Sin techo, un evento con muchos intentos quedaría programado para dentro de días y el bache
        // duraría mucho más que la caída que lo provocó.
        EventOutboxEntity castigado = evento("orders.paid", 10);
        loteDelOutbox(castigado);
        kafkaFalla("orders.paid");

        dispatcher.drain();

        assertThat(castigado.getAttempts()).isEqualTo(11);
        assertThat(castigado.getNextAttemptAt()).isBefore(Instant.now().plus(Duration.ofSeconds(3605)))
                .isAfter(Instant.now().plus(Duration.ofSeconds(3595)));
    }

    @Test
    void trasDoceIntentosElEventoSeDaPorPerdidoYDejaDeReintentarse() {
        // Reintentar para siempre convierte una fila envenenada en un bucle que nadie mira.
        EventOutboxEntity agotado = evento("orders.paid", 11);
        loteDelOutbox(agotado);
        kafkaFalla("orders.paid");

        dispatcher.drain();

        assertThat(agotado.getAttempts()).isEqualTo(12);
        assertThat(agotado.getStatus()).isEqualTo("FAILED");
        assertThat(agotado.getNextAttemptAt()).isEqualTo(YA_VENCIDO);
        verify(repo).save(agotado);
    }

    @Test
    void elFalloDeUnEventoNoImpidePublicarElRestoDelLote() {
        EventOutboxEntity roto = evento("orders.broken", 0);
        EventOutboxEntity sano = evento("orders.paid", 0);
        loteDelOutbox(roto, sano);
        kafkaFalla("orders.broken");
        when(kafka.send(eq("orders.paid"), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        dispatcher.drain();

        ArgumentCaptor<List<UUID>> ids = idsCaptor();
        verify(repo).markSent(ids.capture(), any(Instant.class));
        assertThat(ids.getValue()).containsExactly(sano.getId());
        verify(repo).save(roto);
    }

    @Test
    @DisplayName("Sin cabecera de destino el evento va al Kafka interno, no al bus")
    void sinCabeceraVaAlBrokerInterno() {
        EventOutboxEntity e = evento(null);
        when(repo.claimBatch(any(), any(Pageable.class))).thenReturn(List.of(e));
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        dispatcher.drain();

        verify(kafka).send(eq(e.getTopic()), anyString(), any());
        verify(busKafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Con destino=bus el evento sale por el bus de integración y NUNCA por el interno")
    void conCabeceraVaAlBus() {
        // Es la separación que impide que el catálogo que ven los clientes se mezcle con el tráfico
        // interno de la tienda, y al revés.
        EventOutboxEntity e = evento(Map.of(OutboxDispatcher.CABECERA_DESTINO, OutboxDispatcher.DESTINO_BUS));
        when(repo.claimBatch(any(), any(Pageable.class))).thenReturn(List.of(e));
        when(busKafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        dispatcher.drain();

        verify(busKafka).send(eq(e.getTopic()), anyString(), any());
        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Si el evento pide el bus y el bus está apagado, se reintenta en vez de perderse")
    void busApagadoReintenta() {
        // Descartarlo en silencio dejaría un producto certificado que nunca llega a la tienda.
        when(proveedorBus.getIfAvailable()).thenReturn(null);
        EventOutboxEntity e = evento(Map.of(OutboxDispatcher.CABECERA_DESTINO, OutboxDispatcher.DESTINO_BUS));
        when(repo.claimBatch(any(), any(Pageable.class))).thenReturn(List.of(e));

        dispatcher.drain();

        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo("PENDING");
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now());
        verify(repo, never()).markSent(anyList(), any());
    }

    /** Evento del outbox listo para enviarse, con las cabeceras que se quieran probar. */
    private EventOutboxEntity evento(Map<String, String> cabeceras) {
        EventOutboxEntity e = new EventOutboxEntity();
        e.setId(UUID.randomUUID());
        e.setTopic("catalogo.producto.certificado");
        e.setAggregateType("producto");
        e.setAggregateId("1688-123");
        e.setPartitionKey("1688-123");
        e.setPayload(Map.of("externalId", "1688-123"));
        e.setHeaders(cabeceras);
        e.setStatus("PENDING");
        return e;
    }
}
