package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * DROP-5: one-time view of the HMAC secret used to sign inbound shop order
 * webhooks, plus the URL to post them to.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopInboundSecretDtoOut {

    @Schema(description = "Shared HMAC secret (returned only once)")
    private String inboundSecret;

    @Schema(description = "Inbound order webhook URL")
    private String inboundUrl;
}
