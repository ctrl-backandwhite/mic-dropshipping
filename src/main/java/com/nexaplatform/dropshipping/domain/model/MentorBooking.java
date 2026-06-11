package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a mentor booking. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code mentorId}/{@code learnerId} and the read-only
 * {@code mentorName} (resolved from the mentor's user) the booking view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MentorBooking {

    private UUID id;
    private UUID mentorId;
    private String mentorName;
    private UUID learnerId;
    private Instant startsAt;
    private int durationMin;
    private String status;
    private String topic;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
