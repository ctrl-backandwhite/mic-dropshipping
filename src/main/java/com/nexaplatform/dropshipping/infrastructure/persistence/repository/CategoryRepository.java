package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {
    Optional<CategoryEntity> findBySlug(String slug);
    Optional<CategoryEntity> findBySourceAndExternalId(String source, String externalId);
    List<CategoryEntity> findByParentIsNullOrderByPositionAsc();
    List<CategoryEntity> findByParent_IdOrderByPositionAsc(UUID parentId);
}
