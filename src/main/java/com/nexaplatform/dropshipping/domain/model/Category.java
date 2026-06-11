package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pure domain model for a catalog category. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the read-only {@code productCount} (filled by the use case via an
 * aggregate query) and the flattened {@code names} map / {@code parentId} that
 * the admin view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    private UUID id;
    private String slug;
    private String source;
    private String externalId;
    private String nameZh;
    private Integer position;
    private Boolean active;
    private String icon;
    private UUID parentId;
    private Map<String, String> names;
    private long productCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
