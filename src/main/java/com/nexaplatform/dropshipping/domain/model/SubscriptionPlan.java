package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a subscription plan (no JPA, no DTO concerns).
 * Use cases operate on this model; mappers translate to/from DtoIn/DtoOut
 * (api layer) and to/from the JPA entity (infrastructure layer).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPlan {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private int priceMonthlyCents;
    private int priceYearlyCents;
    private String currency;
    private boolean active;
    private int position;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
