package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** DROP-5: input payload to connect a new shop integration. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopConnectDtoIn {

    @NotBlank
    @Schema(description = "Platform code", example = "shopify")
    private String platform;

    @NotBlank
    @Schema(description = "Shop handle / store identifier")
    private String shopHandle;

    @Schema(description = "OAuth/access token for the shop API")
    private String accessToken;
}
