package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.Map;

/** DROP-445: paginated list of product reviews plus rating histogram. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewListDtoOut {

    @Schema(description = "Reviews in the current page")
    private List<ReviewItemDtoOut> items;

    @Schema(description = "Current page index")
    private int page;

    @Schema(description = "Page size")
    private int size;

    @Schema(description = "Total number of reviews")
    private long totalElements;

    @Schema(description = "Total number of pages")
    private int totalPages;

    @Schema(description = "Rating distribution histogram (stars -> count)")
    private Map<Integer, Long> distribution;

    @Schema(description = "Average rating")
    private double averageRating;
}
