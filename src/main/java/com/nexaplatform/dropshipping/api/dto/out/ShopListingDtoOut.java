package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** DROP-5: view of a product listed into a connected shop. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopListingDtoOut {

    @Schema(description = "Listing id")
    private UUID id;

    @Schema(description = "Catalog product id")
    private UUID productId;

    @Schema(description = "Product title")
    private String productTitle;

    @Schema(description = "Remote product id in the external shop")
    private String remoteProductId;

    @Schema(description = "Listing status")
    private String status;

    @Schema(description = "Last push timestamp")
    private Instant lastPushedAt;
}
