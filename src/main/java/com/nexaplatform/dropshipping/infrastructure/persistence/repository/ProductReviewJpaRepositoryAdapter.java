package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code ProductReviewRepository} domain port. */
public interface ProductReviewJpaRepositoryAdapter extends JpaRepository<ProductReviewEntity, UUID> {

    /** Approved reviews for a product, paginated (newest first). */
    Page<ProductReviewEntity> findByProduct_IdAndApprovedTrueOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    /** Approved reviews for a product filtered by a minimum rating, paginated (newest first). */
    Page<ProductReviewEntity> findByProduct_IdAndApprovedTrueAndRatingGreaterThanEqualOrderByCreatedAtDesc(
            UUID productId, short minRating, Pageable pageable);

    /** Rating distribution histogram (stars -> count). */
    @Query("SELECT r.rating AS stars, COUNT(r) AS cnt " +
           "FROM ProductReviewEntity r " +
           "WHERE r.product.id = :productId AND r.approved = true " +
           "GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> distribution(@Param("productId") UUID productId);

    long countByProduct_IdAndApprovedTrue(UUID productId);
}
