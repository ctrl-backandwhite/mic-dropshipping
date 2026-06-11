package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Transport representation of a winning/best-selling product returned by the
 * Intelligence API. Field names mirror the legacy {@code WinningProduct} record
 * so the frontend contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WinningProductDtoOut {

    @Schema(description = "Product slug")
    private String slug;

    @Schema(description = "Product title in the requested language")
    private String title;

    @Schema(description = "Monthly sales")
    private int monthlySales;

    @Schema(description = "Trend score")
    private BigDecimal trendScore;

    @Schema(description = "Primary image URL")
    private String mainImage;

    @Schema(description = "Base price")
    private BigDecimal price;
}
