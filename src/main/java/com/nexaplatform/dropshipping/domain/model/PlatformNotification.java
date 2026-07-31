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
 * Pure domain model for an in-app notification. Use cases operate on this model;
 * mappers translate to/from DtoOut (api) and the JPA entity (infra). Carries the
 * flattened {@code userId} owning the notification.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformNotification {

    private UUID id;
    private UUID userId;
    private String eventType;
    private String title;
    private String body;
    private String channel;
    private Map<String, Object> payload;
    private Instant readAt;
    @lombok.Builder.Default
    private String status = "NEW";
    private Instant archivedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
