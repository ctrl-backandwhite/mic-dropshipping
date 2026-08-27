package com.nexaplatform.dropshipping.infrastructure.messaging.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EventOutboxRepository extends JpaRepository<EventOutboxEntity, UUID> {

    /**
     * Toma lotes de eventos pendientes con {@code SELECT ... FOR UPDATE SKIP LOCKED}
     * para que múltiples workers (en distintas instancias) puedan procesar el
     * outbox en paralelo sin pisarse.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // -2 es el valor estándar de Jakarta Persistence para SKIP LOCKED.
    //
    // Antes iba con "org.hibernate.lockOptions.skipLocked", un nombre que
    // Hibernate ya no reconoce: lo IGNORABA y avisaba de ello en cada ejecución
    // (HHH90003003), inundando el registro. Y no era solo ruido — sin SKIP
    // LOCKED, los trabajadores de las cuatro réplicas se bloqueaban unos a otros
    // esperando las mismas filas en vez de repartirse el trabajo, que es
    // exactamente lo contrario de lo que este método pretende.
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT e FROM EventOutboxEntity e
            WHERE e.status = 'PENDING' AND e.nextAttemptAt <= :now
            ORDER BY e.nextAttemptAt ASC
            """)
    List<EventOutboxEntity> claimBatch(@Param("now") Instant now, Pageable page);

    @Modifying
    @Query("""
            UPDATE EventOutboxEntity e
               SET e.status = 'SENT', e.sentAt = :now
             WHERE e.id IN :ids
            """)
    int markSent(@Param("ids") List<UUID> ids, @Param("now") Instant now);
}
