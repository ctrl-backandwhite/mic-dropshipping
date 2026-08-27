package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import org.springframework.transaction.annotation.Transactional;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Reclama un correo para ESTE barrido: lo pasa a {@code SENDING} solo si sigue en {@code PENDING}.
     * Devuelve 1 si se lo ha quedado quien llama y 0 si otro se le adelantó.
     *
     * <p>Hace falta porque {@link #findDispatchable} es una lectura sin más: con dos réplicas —lo normal
     * en cuanto se escala— ambos barridos leen la MISMA fila PENDING y el cliente recibe el correo dos
     * veces. El envío SMTP es irreversible, así que no vale con detectarlo después. Este UPDATE
     * condicional es atómico en la base, de modo que solo uno de los dos puede ganarlo; y a diferencia de
     * un {@code SELECT … FOR UPDATE}, no mantiene la fila bloqueada mientras dura el envío, que es lento
     * y depende de un tercero.
     *
     * <p><b>Riesgo residual asumido:</b> si el proceso muere entre reclamar y guardar el resultado, el
     * correo se queda en {@code SENDING} y ningún barrido lo recoge. Es preferible a lo contrario —un
     * correo sin enviar se puede reencolar a mano; uno enviado dos veces al cliente, no—, pero conviene
     * añadir un rescate por antigüedad si algún día se ven filas atascadas en ese estado.
     */
    // @Transactional propio: el barrido NO es transaccional (a propósito, para confirmar correo a correo y
    // que un fallo a mitad de lote no revierta los ya enviados), así que sin esto el UPDATE no tiene
    // transacción donde ejecutarse. Además interesa que sea CORTA: reclamar y confirmar de inmediato, sin
    // arrastrar la transacción durante el envío SMTP.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update OutboundEmailEntity e set e.status = 'SENDING' where e.id = :id and e.status = 'PENDING'")
    int reclamarParaEnvio(@Param("id") UUID id);

    /** Deduplicación de campañas: ¿ya se encoló este template a esta dirección desde {@code createdAt}? */
    boolean existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(String toAddress, String template,
            Instant createdAt);
}
