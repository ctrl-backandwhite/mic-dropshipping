package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.AcademyCourse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link AcademyCourse}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface AcademyCourseRepository extends BaseRepository<AcademyCourse, AcademyCourse, UUID> {

    /** Published courses ordered by creation date (descending) for the storefront listing. */
    List<AcademyCourse> findPublished();

    /** Looks up a course by its unique slug. */
    Optional<AcademyCourse> findBySlug(String slug);
}
