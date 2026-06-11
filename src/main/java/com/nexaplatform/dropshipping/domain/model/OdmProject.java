package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for an ODM/OEM project. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId} owning the project.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OdmProject {

    private UUID id;
    private UUID userId;
    private String kind;
    private String title;
    private String brief;
    private Integer budgetUsdCents;
    private Integer slaDays;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
