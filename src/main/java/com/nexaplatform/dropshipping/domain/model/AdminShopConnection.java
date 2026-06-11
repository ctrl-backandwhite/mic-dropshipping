package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;

/**
 * Pure domain projection for a shop connection row. Read-only model aggregated
 * by the Admin Partners use case from the {@code shop_connection} table; the api
 * mapper translates it to {@code AdminShopConnectionDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminShopConnection {

    private Object id;
    private Object partnerAppId;
    private String platform;
    private String shopHandle;
    private Boolean active;
    private Instant createdAt;
}
