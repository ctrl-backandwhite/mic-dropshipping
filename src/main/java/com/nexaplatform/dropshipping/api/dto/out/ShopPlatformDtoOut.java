package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** DROP-5: a supported shop platform entry (code + display label). */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopPlatformDtoOut {

    @Schema(description = "Platform code", example = "shopify")
    private String code;

    @Schema(description = "Platform display label", example = "Shopify")
    private String label;
}
