package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Use-case port for catalog categories; operates on the {@link Category} domain model. */
public interface CategoryUseCase extends BaseUseCase<Category, Category, UUID> {

    /** Flips the active state of a category and returns the updated model. */
    Category toggle(UUID id);

    /**
     * Indexed, paginated category listing with computed product counts. {@code hasProducts} filters by
     * product count: {@code null} = all, {@code true} = only categories with products, {@code false} =
     * only empty categories (the in-memory filter path is used only when a filter is requested).
     */
    Page<Category> findAllPaged(String q, Boolean hasProducts, Pageable pageable);

    /** Categories that have at least one product (productCount &gt; 0), for catalog filters. */
    List<Category> findWithProducts();

    /** Sets the active state of several categories at once. Returns how many were updated. */
    int setActiveBulk(List<UUID> ids, boolean active);
}
