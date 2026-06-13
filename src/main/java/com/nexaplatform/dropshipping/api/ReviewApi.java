package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.CreateReviewDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.ReviewItemDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReviewListDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * API contract + OpenAPI documentation for the public product reviews resource.
 * The controller only implements these methods; all routing and Swagger
 * documentation live here (springdoc "API interface" pattern).
 */
@Tag(name = "Storefront", description = "Public product reviews")
public interface ReviewApi {

    @Operation(summary = "List reviews for a product")
    @GetMapping
    ResponseEntity<ReviewListDtoOut> list(@PathVariable UUID productId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(required = false) Short minRating);

    @Operation(summary = "Create a review for a product")
    @PostMapping
    ResponseEntity<ReviewItemDtoOut> create(@PathVariable UUID productId, @Valid @RequestBody CreateReviewDtoIn req,
            Authentication auth);
}
