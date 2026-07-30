package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.BaseCrudOperations;

/**
 * Base contract for domain repository ports (mirrors the core's
 * {@code BaseRepository}). Adds the model-based update on top of the shared
 * CRUD contract. Spring Data JPA repositories may still extend
 * {@code JpaRepository} directly; this port is for domain-facing abstractions.
 */
public interface BaseRepository<I, O, K> extends BaseCrudOperations<I, O, K> {

    /**
     * Actualiza el modelo y devuelve el resultado persistido.
     *
     * <p>El {@code default} devolvía {@code null} sin escribir nada, así que un adaptador que se
     * olvidara de implementarlo no fallaba: la escritura se perdía en silencio y el {@code null}
     * reventaba más adelante, lejos de la causa. Pasó de verdad con almacenes y diseños POD, donde
     * editar no guardaba. Ahora el olvido se nota en la primera llamada y dice qué falta.
     *
     * @throws UnsupportedOperationException si el adaptador no lo implementa
     */
    default O update(I model) {
        throw new UnsupportedOperationException(
                getClass().getName() + " no implementa update(): el puerto lo exige para poder escribir.");
    }
}
