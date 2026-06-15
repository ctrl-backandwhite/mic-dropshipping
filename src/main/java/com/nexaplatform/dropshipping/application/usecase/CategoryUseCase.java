package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Use-case port for catalog categories; operates on the {@link Category} domain model. */
public interface CategoryUseCase extends BaseUseCase<Category, Category, UUID> {

    /** Flips the active state of a category and returns the updated model. */
    Category toggle(UUID id);

    /**
     * Indexed, paginated category listing with computed product counts. Backed by a DB query that
     * applies LIMIT/OFFSET (no full-table scan) plus a single GROUP BY for the per-category counts.
     */
    Page<Category> findAllPaged(String q, Pageable pageable);
}
