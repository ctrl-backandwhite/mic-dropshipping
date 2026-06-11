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

    default O save(I model) {
        return null;
    }

    default int saveAll(List<I> models) {
        return 0;
    }

    default List<O> findAll() {
        return Collections.emptyList();
    }

    default int updateAll(List<I> models) {
        return 0;
    }

    default void delete(K id) {
        // no-op by default
    }

    default void deleteAll(List<K> ids) {
        // no-op by default
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
