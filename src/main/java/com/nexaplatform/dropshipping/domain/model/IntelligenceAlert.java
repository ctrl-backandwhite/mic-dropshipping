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
 * Pure domain model for an intelligence alert (DROP-71). Use cases operate on
 * this model; mappers translate to/from DtoIn/DtoOut (api) and the JPA entity
 * (infra). Carries the flattened {@code userId}/{@code categoryId} (the entity
 * holds the relations) and the read-only {@code categoryName} filled when the
 * category is present, which the alert view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntelligenceAlert {

    private UUID id;
    private UUID userId;
    private String keyword;
    private UUID categoryId;
    private String categoryName;
    private String channel;
    private BigDecimal thresholdScore;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
