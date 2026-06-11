package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a sourcing request. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId} used for ownership checks and the
 * read-only {@code quotesCount} (filled by the use case via a quotes query).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingRequest {

    private UUID id;
    private UUID userId;
    private String source;
    private String externalId;
    private String sourceUrl;
    private String titleHint;
    private String status;
    private String planQuota;
    private String notes;
    private UUID selectedQuoteId;
    private long quotesCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
