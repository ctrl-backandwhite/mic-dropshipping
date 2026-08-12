package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmailEntity, UUID> {

    /**
     * Correos listos para enviar: PENDING cuya espera ya venció ({@code next_attempt_at} nulo o pasado).
     * Los aplazados por un fallo temporal (rate-limit) quedan fuera hasta que llegue su hora, así el
     * barrido no los reintenta en cada pasada ni agota sus intentos dentro de la ventana de bloqueo.
     */
    @Query("select e from OutboundEmailEntity e where e.status = 'PENDING' "
            + "and (e.nextAttemptAt is null or e.nextAttemptAt <= :now) order by e.createdAt asc")
    List<OutboundEmailEntity> findDispatchable(@Param("now") Instant now, Limit limit);

    /** Deduplicación de campañas: ¿ya se encoló este template a esta dirección desde {@code createdAt}? */
    boolean existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(String toAddress, String template,
            Instant createdAt);
}
