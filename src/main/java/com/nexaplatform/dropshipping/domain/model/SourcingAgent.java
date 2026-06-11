package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for a sourcing agent (marketplace profile). Carries both the
 * lite and the full-detail fields the API exposes; the DtoMapper projects the
 * subset each endpoint needs. Use cases operate on this model; mappers translate
 * to/from DtoOut (api) and the {@code AgentProfileEntity} (infra).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingAgent {

    private UUID id;
    private String displayName;
    private String tier;
    private String bio;
    private String avatarUrl;
    private List<String> languages;
    private BigDecimal successRate;
    private BigDecimal avgResponseHours;
    private BigDecimal satisfaction;
    private int completedJobs;
    private Integer hourlyRateUsdCents;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
