package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Use-case port for academy enrollments; operates on the {@link AcademyEnrollment} domain model. */
public interface AcademyEnrollmentUseCase extends BaseUseCase<AcademyEnrollment, AcademyEnrollment, UUID> {

    /** Enrolls a user in a course, returning the existing enrollment if one already exists. */
    AcademyEnrollment enroll(UUID userId, UUID courseId);

    /** Lists the enrollments owned by a user. */
    List<AcademyEnrollment> findByUser(UUID userId);

    /** Updates the progress of an enrollment, completing it when it reaches 100%. */
    AcademyEnrollment updateProgress(UUID id, Map<String, Number> body);
}
