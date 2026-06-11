package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.ProductReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

/**
 * Domain repository port for {@link ProductReview}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface ProductReviewRepository extends BaseRepository<ProductReview, ProductReview, UUID> {

    /** Approved reviews for a product, paginated (newest first). */
    Page<ProductReview> findApprovedByProduct(UUID productId, Pageable pageable);

    /** Approved reviews for a product filtered by a minimum rating, paginated (newest first). */
    Page<ProductReview> findApprovedByProductAndMinRating(UUID productId, short minRating, Pageable pageable);

    /** Rating distribution histogram (stars -> count) over the approved reviews of a product. */
    Map<Integer, Long> ratingDistribution(UUID productId);
}
