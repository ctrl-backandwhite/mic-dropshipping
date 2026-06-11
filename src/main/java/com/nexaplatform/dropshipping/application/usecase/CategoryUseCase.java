package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;

import java.util.UUID;

/** Use-case port for catalog categories; operates on the {@link Category} domain model. */
public interface CategoryUseCase extends BaseUseCase<Category, Category, UUID> {

    /** Flips the active state of a category and returns the updated model. */
    Category toggle(UUID id);
}
