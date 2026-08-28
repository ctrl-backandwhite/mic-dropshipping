package com.nexaplatform.dropshipping.infrastructure.messaging.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Worker que drena {@code event_outbox} hacia Kafka. Lectura con
 * {@code FOR UPDATE SKIP LOCKED} para permitir N réplicas trabajando el mismo
 * outbox sin colisiones. Entrega at-least-once con backoff exponencial en
 * caso de fallo del broker.
 */
@Component
@Slf4j
public class OutboxDispatcher {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 12; // ~7 días con backoff 30 s × 2^n cap

    /**
     * Cabecera que decide el destino. Se enruta por cabecera y NO por el nombre del tema: los
     * nombres cambian y un prefijo compartido por accidente mandaría un evento interno al bus de
     * los clientes, que es justo lo que no puede pasar.
     */
    public static final String CABECERA_DESTINO = "destino";
    /** Valor de {@link #CABECERA_DESTINO} para el bus de integración. */
    public static final String DESTINO_BUS = "bus";

    private final EventOutboxRepository repo;
    private final KafkaTemplate<String, Object> kafka;
    /**
     * El bus puede estar apagado —lo está por defecto—, así que el bean puede no existir.
     * ObjectProvider lo tolera; inyectarlo directo impediría arrancar sin bus.
     */
    private final ObjectProvider<KafkaTemplate<String, Object>> busKafka;

    // Constructor explícito, sin @RequiredArgsConstructor: Lombok no copia @Qualifier a los
    // parámetros salvo que se le configure, y sin el cualificador Spring no sabría cuál de los dos
    // KafkaTemplate corresponde a cada campo.
    public OutboxDispatcher(EventOutboxRepository repo,
            KafkaTemplate<String, Object> kafka,
            @Qualifier("busKafkaTemplate") ObjectProvider<KafkaTemplate<String, Object>> busKafka) {
        this.repo = repo;
        this.kafka = kafka;
        this.busKafka = busKafka;
    }

    /** Cada 500 ms intentamos un lote — agresivo pero no asfixia ni Postgres ni Kafka. */
    @Scheduled(fixedDelayString = "${nexadrop.outbox.poll-ms:500}")
    @Transactional
    public void drain() {
        List<EventOutboxEntity> batch = repo.claimBatch(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty())
            return;

        List<UUID> sentIds = new ArrayList<>(batch.size());
        for (EventOutboxEntity e : batch) {
            try {
                CompletableFuture<SendResult<String, Object>> future = plantillaPara(e)
                        .send(e.getTopic(), e.getPartitionKey(), e.getPayload());
                future.get(); // bloquea hasta ack; el ack y el commit van juntos
                sentIds.add(e.getId());
            } catch (Exception ex) {
                // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
                // interrupción deja al pool sin enterarse de que le han pedido parar.
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                e.setAttempts(e.getAttempts() + 1);
                e.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (e.getAttempts() >= MAX_ATTEMPTS) {
                    e.setStatus("FAILED");
                    log.error("Outbox event {} permanently FAILED after {} attempts: {}", e.getId(), e.getAttempts(),
                            ex.getMessage());
                } else {
                    long backoffSec = (long) Math.min(3600, 30L * Math.pow(2, e.getAttempts() - 1.0));
                    e.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(backoffSec)));
                    log.warn("Outbox event {} attempt {} failed; retry in {}s", e.getId(), e.getAttempts(), backoffSec);
                }
                repo.save(e);
            }
        }
        if (!sentIds.isEmpty()) {
            repo.markSent(sentIds, Instant.now());
            log.debug("Outbox -> Kafka: {} events", sentIds.size());
        }
    }

    /**
     * Elige el broker según la cabecera de destino. Si un evento pide el bus y el bus está apagado
     * se LANZA, para que el evento se reintente: descartarlo en silencio dejaría un producto
     * certificado que nunca llega a la tienda y nadie se enteraría hasta echarlo en falta.
     */
    private KafkaTemplate<String, Object> plantillaPara(EventOutboxEntity e) {
        Map<String, String> cabeceras = e.getHeaders();
        boolean alBus = cabeceras != null && DESTINO_BUS.equals(cabeceras.get(CABECERA_DESTINO));
        if (!alBus) {
            return kafka;
        }
        KafkaTemplate<String, Object> plantilla = busKafka.getIfAvailable();
        if (plantilla == null) {
            throw new IllegalStateException(
                    "El evento va al bus de integración pero el bus está apagado (nexadrop.bus.enabled)");
        }
        return plantilla;
    }
}
