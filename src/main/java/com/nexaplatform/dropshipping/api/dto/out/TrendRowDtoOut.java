package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transport representation of an ad trend returned by the Intelligence API.
 * Field names mirror the legacy {@code TrendRow} record so the frontend
 * contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendRowDtoOut {

    @Schema(description = "Ad trend identifier")
    private UUID id;

    @Schema(description = "Ad source", example = "tiktok")
    private String source;

    @Schema(description = "Ad headline")
    private String headline;

    @Schema(description = "Slug of the related product")
    private String productSlug;

    @Schema(description = "Number of impressions")
    private Long impressions;

    @Schema(description = "Engagement count")
    private Long engagement;

    @Schema(description = "Trend score")
    private BigDecimal score;

    @Schema(description = "ISO-2 region code")
    private String region;

    @Schema(description = "When the trend was captured")
    private Instant capturedAt;
}
