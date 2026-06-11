package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DROP-445: public view of a single product review. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewItemDtoOut {

    @Schema(description = "Review id")
    private UUID id;

    @Schema(description = "Reviewed product id")
    private UUID productId;

    @Schema(description = "Author display name")
    private String authorName;

    @Schema(description = "Author country (ISO code)")
    private String authorCountry;

    @Schema(description = "Star rating 1..5")
    private short rating;

    @Schema(description = "Review title")
    private String title;

    @Schema(description = "Review body")
    private String body;

    @Schema(description = "Review tags")
    private List<String> tags;

    @Schema(description = "Number of helpful votes")
    private int helpfulCount;

    @Schema(description = "Whether the purchase was verified")
    private boolean verifiedPurchase;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;
}
