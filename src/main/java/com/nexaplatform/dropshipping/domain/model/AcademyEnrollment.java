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
 * Pure domain model for an academy enrollment. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId}/{@code courseId} and the read-only
 * {@code courseSlug}/{@code courseTitle} the enrollment view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcademyEnrollment {

    private UUID id;
    private UUID userId;
    private UUID courseId;
    private String courseSlug;
    private String courseTitle;
    private BigDecimal progressPct;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
