package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.ReviewApi;
import com.nexaplatform.dropshipping.api.dto.in.CreateReviewDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ReviewItemDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReviewListDtoOut;
import com.nexaplatform.dropshipping.api.mapper.ReviewDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.ProductReviewUseCase;
import com.nexaplatform.dropshipping.domain.model.ProductReview;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * DROP-445: public product reviews with pagination + histogram. Pure
 * implementation of {@link ReviewApi}: injects the {@link ReviewDtoMapper} +
 * {@link ProductReviewUseCase}; maps the use-case read model to the DtoOut;
 * no business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/storefront/catalog/products/{productId}/reviews")
@RequiredArgsConstructor
public class ReviewController implements ReviewApi {

    private final ReviewDtoMapper mapper;
    private final ProductReviewUseCase useCase;

    @Override
    public ResponseEntity<ReviewListDtoOut> list(UUID productId, int page, int size, Short minRating) {
        return ResponseEntity.ok(mapper.toListDtoOut(useCase.list(productId, page, size, minRating)));
    }

    @Override
    public ResponseEntity<ReviewItemDtoOut> create(UUID productId, CreateReviewDtoIn req, Authentication auth) {
        String authorName = req.getAuthorName() != null && !req.getAuthorName().isBlank() ? req.getAuthorName()
                : (auth != null ? auth.getName() : null);
        ProductReview model = ProductReview.builder().rating(req.getRating()).title(req.getTitle()).body(req.getBody())
                .language(req.getLanguage()).authorName(authorName).authorCountry(req.getAuthorCountry()).build();
        return new ResponseEntity<>(mapper.toItem(useCase.create(productId, model)), HttpStatus.CREATED);
    }
}
