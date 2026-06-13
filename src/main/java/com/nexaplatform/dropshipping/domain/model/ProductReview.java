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
 * Pure domain model for a product review. Use cases operate on this model;
 * mappers translate to/from DtoOut (api) and the JPA entity (infra). Carries the
 * flattened {@code productId} relation. The {@code tags} are exposed as a parsed
 * list (the entity stores them comma-separated). {@code approved} is the internal
 * moderation flag used by the list query; it is not exposed on the DtoOut.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductReview {

    private UUID id;
    private UUID productId;
    private String authorName;
    private String authorCountry;
    private short rating;
    private String title;
    private String body;
    private List<String> tags;
    private int helpfulCount;
    private boolean verifiedPurchase;
    private boolean approved;
    private String language;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
