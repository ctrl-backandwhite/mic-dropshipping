package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.AcademyEnrollment;
import com.nexaplatform.dropshipping.domain.repository.AcademyEnrollmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyEnrollmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.AcademyEnrollmentEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyCourseJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyEnrollmentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link AcademyEnrollmentRepository}
 * domain port on top of Spring Data JPA. Owns the persistence-only concerns the
 * domain model abstracts away: resolving the {@code user} and {@code course}
 * relations from the flattened {@code userId}/{@code courseId} on a managed
 * entity.
 */
@Repository
@RequiredArgsConstructor
public class AcademyEnrollmentRepositoryImpl implements AcademyEnrollmentRepository {

    private final AcademyEnrollmentEntityMapper academyEnrollmentEntityMapper;
    private final AcademyEnrollmentJpaRepositoryAdapter academyEnrollmentJpaRepositoryAdapter;
    private final AcademyCourseJpaRepositoryAdapter academyCourseJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public AcademyEnrollment save(AcademyEnrollment model) {
        AcademyEnrollmentEntity entity = resolveEntity(model);
        applyModel(entity, model);
        AcademyEnrollmentEntity saved = academyEnrollmentJpaRepositoryAdapter.save(entity);
        return academyEnrollmentEntityMapper.toDomain(saved);
    }

    @Override
    public List<AcademyEnrollment> findByUserId(UUID userId) {
        return academyEnrollmentEntityMapper.toDomainList(academyEnrollmentJpaRepositoryAdapter.findByUser_Id(userId));
    }

    @Override
    public Optional<AcademyEnrollment> findByUserIdAndCourseId(UUID userId, UUID courseId) {
        return academyEnrollmentJpaRepositoryAdapter.findByUser_IdAndCourse_Id(userId, courseId)
                .map(academyEnrollmentEntityMapper::toDomain);
    }

    @Override
    public AcademyEnrollment update(AcademyEnrollment model) {
        return this.save(model);
    }

    @Override
    public AcademyEnrollment getById(UUID id) {
        return academyEnrollmentJpaRepositoryAdapter.findById(id).map(academyEnrollmentEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        academyEnrollmentJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return academyEnrollmentJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private AcademyEnrollmentEntity resolveEntity(AcademyEnrollment model) {
        if (model.getId() != null) {
            return academyEnrollmentJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Enrollment"));
        }
        return new AcademyEnrollmentEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving user/course relations. */
    private void applyModel(AcademyEnrollmentEntity entity, AcademyEnrollment model) {
        if (model.getUserId() != null) {
            entity.setUser(userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User")));
        }
        if (model.getCourseId() != null) {
            entity.setCourse(academyCourseJpaRepositoryAdapter.findById(model.getCourseId())
                    .orElseThrow(() -> new NotFoundException("Course")));
        }
        if (model.getProgressPct() != null) {
            entity.setProgressPct(model.getProgressPct());
        }
        entity.setCompletedAt(model.getCompletedAt());
    }
}
