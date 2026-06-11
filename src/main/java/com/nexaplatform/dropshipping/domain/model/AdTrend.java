package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for an ad trend (DROP-8). Use cases operate on this model;
 * mappers translate to/from DtoOut (api) and the JPA entity (infra). The
 * {@code AdTrendEntity} carries no audit columns, so the model only exposes the
 * captured-at marker the intelligence view needs.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdTrend {

    private UUID id;
    private String source;
    private String headline;
    private String productSlug;
    private Long impressions;
    private Long engagement;
    private BigDecimal score;
    private String region;
    private Instant capturedAt;
}
