package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of starting a subscription checkout. Replaces the legacy
 * {@code BillingDtos.SubscribeResponse} record; field names are kept identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscribeDtoOut {

    @Schema(description = "URL the customer must be redirected to in order to complete checkout")
    private String checkoutUrl;

    @Schema(description = "Provider checkout session id (or local subscription id in dev mode)")
    private String sessionId;
}
