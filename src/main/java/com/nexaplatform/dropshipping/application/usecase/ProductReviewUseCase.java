package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.domain.model.ProductReviewPage;

import java.util.UUID;

/** Use-case port for product reviews; operates on the {@link ProductReview} domain model. */
public interface ProductReviewUseCase extends BaseUseCase<ProductReview, ProductReview, UUID> {

    /**
     * Lists approved reviews for a product with a rating histogram and average.
     * Returns the {@link ProductReviewPage} read model the api mapper turns into
     * the storefront DtoOut.
     */
    ProductReviewPage list(UUID productId, int page, int size, Short minRating);

    /** Crea una reseña (aprobada) y recalcula la valoración media y el contador del producto. */
    ProductReview create(UUID productId, ProductReview review);
}
