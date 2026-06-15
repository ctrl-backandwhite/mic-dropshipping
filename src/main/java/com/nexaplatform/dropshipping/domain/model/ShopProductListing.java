package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a product listed into a connected shop (sub-entity of the
 * {@link ShopConnection} aggregate). Use cases operate on this model; mappers
 * translate to/from the DtoOut (api) and the JPA entity (infra). Carries the
 * read-only {@code productTitle} (flattened from the related product) the admin
 * view exposes.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopProductListing {

    private UUID id;
    private UUID shopConnectionId;
    private UUID productId;
    private String productTitle;
    private String remoteProductId;
    private String status;
    private String errorMessage;
    private Instant lastPushedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
