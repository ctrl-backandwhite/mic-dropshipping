package com.nexaplatform.dropshipping.domain;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared CRUD contract inherited by {@code BaseUseCase} and {@code BaseRepository}
 * (mirrors the core's {@code BaseCrudOperations}). All methods are {@code default}
 * so implementors only override what they need.
 *
 * @param <I> input / command type
 * @param <O> output / result type
 * @param <K> identifier type
 */
public interface BaseCrudOperations<I, O, K> {

    /**
     * Los métodos de ESCRITURA no traen implementación por defecto que finja haber funcionado. Antes
     * devolvían {@code null} / {@code 0} / no hacían nada, así que un adaptador que se olvidara de
     * implementarlos perdía la escritura en silencio y el fallo aparecía mucho después y en otro sitio.
     * Pasó con almacenes, diseños POD y el sembrado de reglas de precio. Ahora el olvido se nota en la
     * primera llamada. Las CONSULTAS sí conservan un valor neutro: ahí no hay nada que perder.
     *
     * @throws UnsupportedOperationException si el implementador no lo sobreescribe
     */
    default O save(I model) {
        throw new UnsupportedOperationException(getClass().getName() + " no implementa save().");
    }

    /** @throws UnsupportedOperationException si el implementador no lo sobreescribe. Ver {@link #save}. */
    default int saveAll(List<I> models) {
        throw new UnsupportedOperationException(getClass().getName() + " no implementa saveAll().");
    }

    default List<O> findAll() {
        return Collections.emptyList();
    }

    /** @throws UnsupportedOperationException si el implementador no lo sobreescribe. Ver {@link #save}. */
    default int updateAll(List<I> models) {
        throw new UnsupportedOperationException(getClass().getName() + " no implementa updateAll().");
    }

    /** @throws UnsupportedOperationException si el implementador no lo sobreescribe. Ver {@link #save}. */
    default void delete(K id) {
        throw new UnsupportedOperationException(getClass().getName() + " no implementa delete().");
    }

    /** @throws UnsupportedOperationException si el implementador no lo sobreescribe. Ver {@link #save}. */
    default void deleteAll(List<K> ids) {
        throw new UnsupportedOperationException(getClass().getName() + " no implementa deleteAll().");
    }

    default O getById(K id) {
        return null;
    }

    default List<O> getByIds(List<K> ids) {
        return Collections.emptyList();
    }

    default List<O> findAllPagedAndSorted(int page, int size, String sortBy, boolean ascending) {
        return Collections.emptyList();
    }

    default List<O> findAllFiltered(Map<String, Object> filters) {
        return Collections.emptyList();
    }

    default boolean existsById(K id) {
        return false;
    }
}
