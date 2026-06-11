package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload to create an intelligence alert. Mirrors the legacy
 * {@code AlertRequest} record so the frontend contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRequestDtoIn {

    @Schema(description = "Keyword to watch")
    private String keyword;

    @Schema(description = "Optional category scope")
    private UUID categoryId;

    @Schema(description = "Delivery channel (defaults to EMAIL)", example = "EMAIL")
    private String channel;

    @Schema(description = "Trend score threshold that triggers the alert")
    private BigDecimal thresholdScore;
}
