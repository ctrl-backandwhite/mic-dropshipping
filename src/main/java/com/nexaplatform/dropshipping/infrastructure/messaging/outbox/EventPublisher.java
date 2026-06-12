package com.nexaplatform.dropshipping.infrastructure.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * API de aplicación: en lugar de llamar a {@code kafkaTemplate.send} directo,
 * los servicios de dominio llaman a {@link #publish} dentro de su misma
 * transacción de BD. La fila entra en {@code event_outbox} y el cambio de
 * dominio y el evento comparten suerte (commit/rollback atómicos).
 * <p>
 * El worker {@link OutboxDispatcher} es el único responsable de hablar con Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final EventOutboxRepository repo;
    private final ObjectMapper mapper;

    /** Publica un evento dentro de la transacción actual (sin nueva tx). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String aggregateType, String aggregateId, String partitionKey, Object payload) {
        publish(topic, aggregateType, aggregateId, partitionKey, payload, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String aggregateType, String aggregateId, String partitionKey, Object payload,
            Map<String, String> headers) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = mapper.convertValue(payload, Map.class);
        EventOutboxEntity row = EventOutboxEntity.builder().aggregateType(aggregateType).aggregateId(aggregateId)
                .topic(topic).partitionKey(partitionKey != null ? partitionKey : aggregateId).payload(json)
                .headers(headers).build();
        repo.save(row);
        log.debug("Outbox <- topic={} agg={}/{}", topic, aggregateType, aggregateId);
    }
}
