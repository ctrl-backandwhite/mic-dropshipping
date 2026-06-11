package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyEnrollmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code AcademyEnrollmentRepository} domain port. */
public interface AcademyEnrollmentJpaRepositoryAdapter extends JpaRepository<AcademyEnrollmentEntity, UUID> {

    List<AcademyEnrollmentEntity> findByUser_Id(UUID userId);

    Optional<AcademyEnrollmentEntity> findByUser_IdAndCourse_Id(UUID userId, UUID courseId);
}
