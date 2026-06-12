package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyCourseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademyCourseRepository extends JpaRepository<AcademyCourseEntity, UUID> {
    List<AcademyCourseEntity> findByPublishedTrueOrderByCreatedAtDesc();

    Optional<AcademyCourseEntity> findBySlug(String slug);
}
