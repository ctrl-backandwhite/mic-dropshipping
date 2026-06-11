package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.ReviewItemDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReviewListDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.ReviewDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DROP-445: use-case service for public product reviews. Holds all logic that
 * used to live inside {@code ReviewController}: pagination, optional rating
 * filtering and the rating histogram / average computation.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ProductReviewRepository reviewRepo;
    private final ProductRepository productRepo;
    private final ReviewDtoMapper reviewDtoMapper;

    /** Lists approved reviews for a product with a rating histogram and average. */
    @Transactional(readOnly = true)
    public ReviewListDtoOut list(UUID productId, int page, int size, Short minRating) {
        if (!productRepo.existsById(productId)) throw new NotFoundException("Product");
        PageRequest pr = PageRequest.of(page, Math.min(size, 50));
        Page<ProductReviewEntity> result = minRating != null && minRating > 0
                ? reviewRepo.findByProduct_IdAndApprovedTrueAndRatingGreaterThanEqualOrderByCreatedAtDesc(productId, minRating, pr)
                : reviewRepo.findByProduct_IdAndApprovedTrueOrderByCreatedAtDesc(productId, pr);

        List<ReviewItemDtoOut> items = reviewDtoMapper.toItemList(result.getContent());

        // Rating distribution histogram 5..1.
        Map<Integer, Long> dist = new HashMap<>();
        for (int s = 1; s <= 5; s++) dist.put(s, 0L);
        for (Object[] row : reviewRepo.distribution(productId)) {
            dist.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        double avg = total == 0 ? 0.0
                : dist.entrySet().stream().mapToDouble(e -> e.getKey() * e.getValue()).sum() / total;

        return ReviewListDtoOut.builder()
                .items(items)
                .page(page)
                .size(pr.getPageSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .distribution(dist)
                .averageRating(Math.round(avg * 10) / 10.0)
                .build();
    }
}
