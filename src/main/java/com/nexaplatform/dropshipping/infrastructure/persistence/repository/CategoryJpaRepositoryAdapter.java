package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code CategoryRepository} domain port. */
public interface CategoryJpaRepositoryAdapter extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findBySlug(String slug);

    /**
     * Loads every category with its translations in a single query. Fixes the N+1 that made the admin
     * category tree slow: the domain mapper builds the {@code names} map from the lazy {@code translations}
     * collection, so a plain {@code findAll()} issued one extra query per category.
     */
    @Query("SELECT DISTINCT c FROM CategoryEntity c LEFT JOIN FETCH c.translations")
    List<CategoryEntity> findAllWithTranslations();

    /**
     * Indexed, paginated category listing filtered by a non-null free-text term (slug, Chinese name or any
     * translated name). The DB applies LIMIT/OFFSET and the ordering uses the {@code (parent_id, position)}
     * / {@code position} indexes (migration v57). Callers pass a non-null {@code q}; the no-filter case
     * uses {@code findAll(Pageable)} (a null bind here would make Postgres infer {@code lower(bytea)}).
     */
    @Query("""
            SELECT c FROM CategoryEntity c
            WHERE LOWER(c.slug) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.nameZh) LIKE LOWER(CONCAT('%', :q, '%'))
               OR EXISTS (SELECT 1 FROM CategoryTranslationEntity t
                          WHERE t.category = c AND LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<CategoryEntity> search(@Param("q") String q, Pageable pageable);
}
