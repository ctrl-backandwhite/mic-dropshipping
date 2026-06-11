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
 * Pure domain model for a mentor profile. Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the flattened {@code userId} and the read-only {@code displayName}
 * (resolved from the user, with {@code email} kept so the use case can apply the
 * seed-account filter) the mentor view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MentorProfile {

    private UUID id;
    private UUID userId;
    private String displayName;
    private String email;
    private String headline;
    private String bio;
    private List<String> expertise;
    private List<String> languages;
    private int hourlyRateUsdCents;
    private String timezone;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
