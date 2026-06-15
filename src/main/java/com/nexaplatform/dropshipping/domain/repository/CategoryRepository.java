package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Category}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface CategoryRepository extends BaseRepository<Category, Category, UUID> {

    /** Looks up a category by its unique slug (used for slug-uniqueness checks). */
    Optional<Category> findBySlug(String slug);

    /** Indexed, paginated listing with an optional free-text filter (slug / name / translated name). */
    Page<Category> search(String q, Pageable pageable);
}
