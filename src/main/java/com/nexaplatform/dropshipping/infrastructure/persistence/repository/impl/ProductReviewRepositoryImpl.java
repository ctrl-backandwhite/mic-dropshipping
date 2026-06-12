package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.domain.repository.ProductReviewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductReviewEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link ProductReviewRepository} domain
 * port on top of Spring Data JPA. Preserves the legacy storefront contract: only
 * approved reviews, newest first, with an optional minimum-rating filter, plus
 * the rating histogram. Page content is mapped to domain models; the histogram
 * rows are collapsed into a {stars -> count} map.
 */
@Repository
@RequiredArgsConstructor
public class ProductReviewRepositoryImpl implements ProductReviewRepository {

    private final ProductReviewEntityMapper productReviewEntityMapper;
    private final ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;

    @Override
    public Page<ProductReview> findApprovedByProduct(UUID productId, Pageable pageable) {
        return productReviewJpaRepositoryAdapter
                .findByProduct_IdAndApprovedTrueOrderByCreatedAtDesc(productId, pageable)
                .map(productReviewEntityMapper::toDomain);
    }

    @Override
    public Page<ProductReview> findApprovedByProductAndMinRating(UUID productId, short minRating, Pageable pageable) {
        return productReviewJpaRepositoryAdapter
                .findByProduct_IdAndApprovedTrueAndRatingGreaterThanEqualOrderByCreatedAtDesc(productId, minRating,
                        pageable)
                .map(productReviewEntityMapper::toDomain);
    }

    @Override
    public Map<Integer, Long> ratingDistribution(UUID productId) {
        Map<Integer, Long> dist = new HashMap<>();
        for (Object[] row : productReviewJpaRepositoryAdapter.distribution(productId)) {
            dist.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return dist;
    }

    @Override
    public ProductReview getById(UUID id) {
        return productReviewJpaRepositoryAdapter.findById(id).map(productReviewEntityMapper::toDomain).orElse(null);
    }

    @Override
    public boolean existsById(UUID id) {
        return productReviewJpaRepositoryAdapter.existsById(id);
    }

    @Override
    public void delete(UUID id) {
        productReviewJpaRepositoryAdapter.deleteById(id);
    }
}
