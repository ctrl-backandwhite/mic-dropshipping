package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** DROP-5: view of a connected shop integration. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopDtoOut {

    @Schema(description = "Shop connection id")
    private UUID id;

    @Schema(description = "Platform code", example = "shopify")
    private String platform;

    @Schema(description = "Shop handle / store identifier")
    private String shopHandle;

    @Schema(description = "Connection status")
    private String status;

    @Schema(description = "Last sync timestamp")
    private Instant lastSyncAt;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Number of product listings in this shop")
    private int listings;
}
