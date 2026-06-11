package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a supplier. Use cases operate on this model; mappers
 * translate to/from DtoOut (api) and the JPA entity (infra). Besides the
 * persisted business fields it carries read-only/computed fields the admin view
 * needs (product count and rating-derived KPIs) which the use case fills.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Supplier {

    private UUID id;
    private String externalId;
    private String source;
    private String name;
    private String nameZh;
    private String country;
    private String city;
    private BigDecimal rating;
    private Integer yearsActive;
    private boolean verified;
    private boolean trustPass;
    private String profileUrl;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    // Read-only/computed fields filled by the use case for the admin list view.
    private long productCount;
    private long onTimePct;
    private double defectRate;
    private int responseHours;
    private int leadTimeDays;
}
