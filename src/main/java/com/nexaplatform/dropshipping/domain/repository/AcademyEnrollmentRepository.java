package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link AcademyEnrollment}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface AcademyEnrollmentRepository extends BaseRepository<AcademyEnrollment, AcademyEnrollment, UUID> {

    /** All enrollments owned by a user. */
    List<AcademyEnrollment> findByUserId(UUID userId);

    /** Existing enrollment for a (user, course) pair, used to dedup enroll. */
    Optional<AcademyEnrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);
}
