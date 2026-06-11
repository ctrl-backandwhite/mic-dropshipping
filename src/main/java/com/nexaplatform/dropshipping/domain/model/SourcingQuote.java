package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a competing quote on a sourcing request. Carries the
 * flattened {@code requestId} and the nested {@link SourcingAgent} the quote view
 * exposes. Use cases operate on this model; mappers translate to/from DtoOut (api)
 * and the {@code SourcingQuoteEntity} (infra).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingQuote {

    private UUID id;
    private UUID requestId;
    private SourcingAgent agent;
    private int priceUsdCents;
    private int etaDays;
    private Integer moq;
    private String notes;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
