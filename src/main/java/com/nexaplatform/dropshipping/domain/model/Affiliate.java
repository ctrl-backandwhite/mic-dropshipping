package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for an affiliate account. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId} the get-or-create use case keys on.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Affiliate {

    private UUID id;
    private UUID userId;
    private String code;
    private long earningsUsdCents;
    private long payoutUsdCents;
    private int referralsCount;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
