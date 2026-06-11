package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for an academy course. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcademyCourse {

    private UUID id;
    private String slug;
    private String title;
    private String description;
    private String instructor;
    private Integer durationMinutes;
    private String coverUrl;
    private String videoUrl;
    private String locale;
    private String level;
    private boolean published;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
