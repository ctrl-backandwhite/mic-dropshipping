package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.Map;

/**
 * Read model carrying one page of {@link ProductReview} together with the
 * list-level summary the storefront view exposes: pagination metadata, the
 * rating histogram (stars -> count) and the computed average. The use case fills
 * these computed/read-only fields (analogous to {@code Category.productCount});
 * the api mapper turns this model into {@code ReviewListDtoOut}, preserving the
 * exact JSON field names.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductReviewPage {

    private List<ProductReview> items;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private Map<Integer, Long> distribution;
    private double averageRating;
}
