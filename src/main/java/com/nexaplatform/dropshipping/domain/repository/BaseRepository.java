package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.BaseCrudOperations;

/**
 * Base contract for domain repository ports (mirrors the core's
 * {@code BaseRepository}). Adds the model-based update on top of the shared
 * CRUD contract. Spring Data JPA repositories may still extend
 * {@code JpaRepository} directly; this port is for domain-facing abstractions.
 */
public interface BaseRepository<I, O, K> extends BaseCrudOperations<I, O, K> {

    default O update(I model) {
        return null;
    }
}
