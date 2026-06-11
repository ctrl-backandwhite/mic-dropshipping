package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.ReviewApi;
import com.nexaplatform.dropshipping.api.dto.out.ReviewListDtoOut;
import com.nexaplatform.dropshipping.application.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** DROP-445: public product reviews with pagination + histogram. */
@RestController
@RequestMapping("/api/storefront/catalog/products/{productId}/reviews")
@RequiredArgsConstructor
public class ReviewController implements ReviewApi {

    private final ReviewService reviewService;

    @Override
    public ResponseEntity<ReviewListDtoOut> list(UUID productId, int page, int size, Short minRating) {
        return ResponseEntity.ok(reviewService.list(productId, page, size, minRating));
    }
}
