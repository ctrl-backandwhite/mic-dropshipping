package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.ProductReviewUseCase;
import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.domain.model.ProductReviewPage;
import com.nexaplatform.dropshipping.domain.repository.ProductReviewRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductReviewEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DROP-445: product-reviews use case. Operates on the {@link ProductReview} model
 * and delegates persistence to the domain port. Holds all logic that used to live
 * in {@code ReviewService}: product-existence guard, pagination, optional rating
 * filtering and the rating histogram / average computation. The
 * {@link ProductRepository} is kept as a collaborator for the existence check.
 */
@Service
@RequiredArgsConstructor
public class ProductReviewUseCaseImpl implements ProductReviewUseCase {

    private final ProductReviewRepository productReviewRepository;
    private final ProductRepository productRepo;
    private final ProductReviewJpaRepositoryAdapter reviewJpa;
    private final ProductReviewEntityMapper reviewEntityMapper;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userRepository;

    /** Lists approved reviews for a product with a rating histogram and average. */
    @Override
    @Transactional(readOnly = true)
    public ProductReviewPage list(UUID productId, int page, int size, Short minRating) {
        if (!productRepo.existsById(productId))
            throw new NotFoundException("Product");
        PageRequest pr = PageRequest.of(page, Math.min(size, 50));
        Page<ProductReview> result = minRating != null && minRating > 0
                ? productReviewRepository.findApprovedByProductAndMinRating(productId, minRating, pr)
                : productReviewRepository.findApprovedByProduct(productId, pr);

        List<ProductReview> items = result.getContent();

        // Rating distribution histogram 5..1.
        Map<Integer, Long> dist = productReviewRepository.ratingDistribution(productId);
        for (int s = 1; s <= 5; s++)
            dist.putIfAbsent(s, 0L);
        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        double avg = total == 0
                ? 0.0
                : dist.entrySet().stream().mapToDouble(e -> e.getKey() * e.getValue()).sum() / total;

        return ProductReviewPage.builder().items(items).page(page).size(pr.getPageSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).distribution(dist)
                .averageRating(Math.round(avg * 10) / 10.0).build();
    }

    @Override
    @Transactional
    public ProductReview create(UUID productId, ProductReview review, UUID userId) {
        ProductEntity product = productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product"));
        // La reseña se ATA al usuario autenticado y el nombre/país los pone el SERVIDOR desde su perfil,
        // ignorando lo que venga en el cuerpo: si no, cualquiera podía firmar como "NexaPlatform Oficial"
        // (suplantación) y quedaban autopublicadas sin dueño.
        var author = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User"));
        String authorName = author.getDisplayName() != null && !author.getDisplayName().isBlank()
                ? author.getDisplayName()
                : (author.getFirstName() != null && !author.getFirstName().isBlank() ? author.getFirstName()
                        : "Anónimo");
        short rating = (short) Math.clamp(review.getRating(), 1, 5);
        ProductReviewEntity entity = ProductReviewEntity.builder()
                .product(product)
                .user(author)
                .authorName(authorName)
                .authorCountry(author.getCountry()).rating(rating).title(review.getTitle()).body(review.getBody())
                .language(review.getLanguage() != null && !review.getLanguage().isBlank()
                        ? review.getLanguage().toLowerCase() : "es")
                // Escrita en la plataforma, no importada del proveedor. Sin compra verificada asociada aún.
                .helpfulCount(0).verifiedPurchase(false).source(ReviewSource.CUSTOMER).approved(true).build();
        ProductReviewEntity saved = reviewJpa.save(entity);

        // Recalcular media y contador del producto (solo reseñas aprobadas).
        Map<Integer, Long> dist = productReviewRepository.ratingDistribution(productId);
        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        double avg = total == 0 ? 0.0
                : dist.entrySet().stream().mapToDouble(e -> e.getKey() * e.getValue()).sum() / total;
        product.setReviewCount((int) total);
        product.setRating(BigDecimal.valueOf(Math.round(avg * 100) / 100.0));
        productRepo.save(product);

        return reviewEntityMapper.toDomain(saved);
    }
}
