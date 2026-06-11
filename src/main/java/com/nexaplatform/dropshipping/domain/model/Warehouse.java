package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a warehouse. Use cases operate on this model; mappers
 * translate to/from DtoOut (api) and the JPA entity (infra).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warehouse {

    private UUID id;
    private String code;
    private String name;
    private String country;
    private String city;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
