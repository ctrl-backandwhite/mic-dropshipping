package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a support ticket. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId} owning the ticket and the flattened
 * {@code orderId} the ticket optionally references.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicket {

    private UUID id;
    private UUID userId;
    private String kind;
    private String subject;
    private String body;
    private UUID orderId;
    private String status;
    private String priority;
    private String resolution;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
