package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.BaseCrudOperations;

/**
 * Base contract for application use cases (mirrors the core's {@code BaseUseCase}).
 * Adds the single-entity update operation on top of the shared CRUD contract.
 */
public interface BaseUseCase<I, O, K> extends BaseCrudOperations<I, O, K> {

    default O update(I model, K id) {
        return null;
    }
}
