package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProductReviewRepository extends JpaRepository<ProductReviewEntity, UUID> {

    /** Reviews aprobadas de un producto, paginadas. */
    Page<ProductReviewEntity> findByProduct_IdAndApprovedTrueOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /** Filtrado por rating mínimo. */
    Page<ProductReviewEntity> findByProduct_IdAndApprovedTrueAndRatingGreaterThanEqualOrderByCreatedAtDesc(
            UUID productId, short minRating, Pageable pageable);

    /** Histograma de distribución de ratings (stars → count). */
    @Query("SELECT r.rating AS stars, COUNT(r) AS cnt " +
           "FROM ProductReviewEntity r " +
           "WHERE r.product.id = :productId AND r.approved = true " +
           "GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> distribution(@Param("productId") UUID productId);

    long countByProduct_IdAndApprovedTrue(UUID productId);
}
