package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {
    Optional<CategoryEntity> findBySlug(String slug);

    Optional<CategoryEntity> findBySourceAndExternalId(String source, String externalId);

    List<CategoryEntity> findByParentIsNullOrderByPositionAsc();

    List<CategoryEntity> findByParent_IdOrderByPositionAsc(UUID parentId);

    /**
     * Loads every category with its translations in one query (avoids the per-category translations
     * N+1 when building the storefront category tree in memory).
     */
    @Query("SELECT DISTINCT c FROM CategoryEntity c LEFT JOIN FETCH c.translations")
    List<CategoryEntity> findAllWithTranslations();

    /** Product counts per category in a single GROUP BY: rows of {@code [categoryId, count]}. */
    @Query("SELECT p.category.id, COUNT(p) FROM ProductEntity p WHERE p.category IS NOT NULL GROUP BY p.category.id")
    List<Object[]> productCountByCategory();
}
