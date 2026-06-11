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
 * Pure domain model for a print-on-demand design. Use cases operate on this
 * model; mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the read-only {@code productTitle} (flattened from the related product)
 * and the flattened {@code productId} that the design view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodDesign {

    private UUID id;
    private UUID userId;
    private UUID productId;
    private String productTitle;
    private String name;
    private Map<String, Object> canvasJson;
    private String mockupUrl;
    private String status;
    private String aiPrompt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
