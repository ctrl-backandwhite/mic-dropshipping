package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pure domain model for a webhook subscription (no JPA, no DTO concerns).
 * Use cases operate on this model; mappers translate to/from DtoIn/DtoOut
 * (api layer) and to/from the JPA entity (infrastructure layer).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSubscription {

    private UUID id;
    private String name;
    private String targetUrl;
    private String secret;
    private List<String> events;
    private boolean active;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
