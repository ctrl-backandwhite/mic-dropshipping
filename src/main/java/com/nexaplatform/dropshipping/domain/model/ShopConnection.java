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
 * Pure domain model for a user's shop integration (Shopify, Woo, TikTok, eBay,
 * Amazon, BigCommerce, Wix, Shopee, Lazada…). Use cases operate on this model;
 * mappers translate to/from DtoIn/DtoOut (api) and the JPA entity (infra).
 * Carries the owning {@code userId}, the encrypted access token and the metadata
 * map (where the inbound HMAC secret lives) plus the read-only {@code listings}
 * count the admin view exposes (filled by the use case via an aggregate query).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopConnection {

    private UUID id;
    private UUID userId;
    private String platform;
    private String shopHandle;
    private String accessTokenEnc;
    private String status;
    private Instant lastSyncAt;
    private Map<String, Object> metadata;
    private int listings;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
