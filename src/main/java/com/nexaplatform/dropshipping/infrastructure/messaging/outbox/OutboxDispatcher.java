package com.nexaplatform.dropshipping.infrastructure.messaging.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Worker que drena {@code event_outbox} hacia Kafka. Lectura con
 * {@code FOR UPDATE SKIP LOCKED} para permitir N réplicas trabajando el mismo
 * outbox sin colisiones. Entrega at-least-once con backoff exponencial en
 * caso de fallo del broker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 12; // ~7 días con backoff 30 s × 2^n cap

    private final EventOutboxRepository repo;
    private final KafkaTemplate<String, Object> kafka;

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
                CompletableFuture<SendResult<String, Object>> future = kafka.send(e.getTopic(), e.getPartitionKey(),
                        e.getPayload());
                future.get(); // bloquea hasta ack; el ack y el commit van juntos
                sentIds.add(e.getId());
            } catch (Exception ex) {
                e.setAttempts(e.getAttempts() + 1);
                e.setLastError(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (e.getAttempts() >= MAX_ATTEMPTS) {
                    e.setStatus("FAILED");
                    log.error("Outbox event {} permanently FAILED after {} attempts: {}", e.getId(), e.getAttempts(),
                            ex.getMessage());
                } else {
                    long backoffSec = (long) Math.min(3600, 30L * Math.pow(2, e.getAttempts() - 1));
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
}
