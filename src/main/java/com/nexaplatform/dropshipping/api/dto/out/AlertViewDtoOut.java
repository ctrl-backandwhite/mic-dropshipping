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
 * Transport representation of an intelligence alert returned by the Intelligence
 * API. Field names mirror the legacy {@code AlertView} record so the frontend
 * contract stays identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertViewDtoOut {

    @Schema(description = "Alert identifier")
    private UUID id;

    @Schema(description = "Keyword being watched")
    private String keyword;

    @Schema(description = "Scoped category id (null if not scoped)")
    private UUID categoryId;

    @Schema(description = "Scoped category name (null if not scoped)")
    private String categoryName;

    @Schema(description = "Delivery channel", example = "EMAIL")
    private String channel;

    @Schema(description = "Trend score threshold that triggers the alert")
    private BigDecimal thresholdScore;

    @Schema(description = "Whether the alert is active")
    private boolean active;

    @Schema(description = "When the alert was created")
    private Instant createdAt;
}
