package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/**
 * Pure read-only domain model for a "winning product" (DROP-8): a market-
 * intelligence projection over the catalog used by the sales-trends and
 * winning-products endpoints. It is not a persisted aggregate; the use case
 * builds it from the product read model (title resolved in the user's language,
 * primary image, monthly sales, trend score and price).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WinningProduct {

    private String slug;
    private String title;
    private int monthlySales;
    private BigDecimal trendScore;
    private String mainImage;
    private BigDecimal price;
}
