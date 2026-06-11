package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Pure domain model for the one-time inbound HMAC secret used to sign shop order
 * webhooks, plus the URL to post them to. Produced by the use case on rotation;
 * mappers translate it to the transport DtoOut.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopInboundSecret {

    private String inboundSecret;
    private String inboundUrl;
}
