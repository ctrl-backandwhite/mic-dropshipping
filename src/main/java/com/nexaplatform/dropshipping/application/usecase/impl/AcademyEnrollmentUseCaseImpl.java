package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.AcademyEnrollmentUseCase;
import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;
import com.nexaplatform.dropshipping.domain.repository.AcademyCourseRepository;
import com.nexaplatform.dropshipping.domain.repository.AcademyEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Academy-enrollment use case. Operates on the {@link AcademyEnrollment} model and
 * delegates persistence to the domain port. Holds the logic that used to live in
 * {@code AcademyController}: deduping enrollment by (user, course), listing the
 * current user's enrollments and clamping/completing progress updates. All
 * mutations are scoped to the authenticated user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcademyEnrollmentUseCaseImpl implements AcademyEnrollmentUseCase {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AcademyEnrollmentRepository academyEnrollmentRepository;
    private final AcademyCourseRepository academyCourseRepository;

    @Override
    @Transactional
    public AcademyEnrollment enroll(UUID userId, UUID courseId) {
        if (!academyCourseRepository.existsById(courseId)) {
            throw new NotFoundException("Course");
        }
        return academyEnrollmentRepository.findByUserIdAndCourseId(userId, courseId)
                .orElseGet(() -> academyEnrollmentRepository
                        .save(AcademyEnrollment.builder().userId(userId).courseId(courseId)
                                .progressPct(BigDecimal.ZERO).build()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AcademyEnrollment> findByUser(UUID userId) {
        return academyEnrollmentRepository.findByUserId(userId);
    }

    @Override
    @Transactional
    public AcademyEnrollment updateProgress(UUID userId, UUID id, Map<String, Number> body) {
        AcademyEnrollment existing = academyEnrollmentRepository.getById(id);
        // IDOR: la matrícula debe ser del usuario autenticado. Si no existe o es de otro, 404 (no filtramos
        // la existencia de matrículas ajenas ni permitimos modificar su progreso).
        if (Objects.isNull(existing) || !userId.equals(existing.getUserId())) {
            throw new NotFoundException("Enrollment");
        }
        Number pct = body.get("progressPct");
        if (pct != null) {
            BigDecimal p = new BigDecimal(pct.toString()).min(HUNDRED);
            existing.setProgressPct(p);
            if (p.compareTo(HUNDRED) >= 0 && existing.getCompletedAt() == null) {
                existing.setCompletedAt(Instant.now());
            }
        }
        return academyEnrollmentRepository.update(existing);
    }
}
