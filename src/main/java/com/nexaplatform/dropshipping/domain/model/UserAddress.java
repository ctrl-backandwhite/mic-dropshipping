package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a user's shipping address. Use cases operate on this
 * model; mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * The owning user is carried as {@code userId} (the relation is resolved in the
 * infrastructure adapter). The default flag uses the property name {@code default}
 * (accessed via {@code isDefault()}), matching the entity and the frontend contract.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAddress {

    private UUID id;
    private UUID userId;
    private String label;
    private String fullName;
    private String phone;
    private String line1;
    private String line2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
