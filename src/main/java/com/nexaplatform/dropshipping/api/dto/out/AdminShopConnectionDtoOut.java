package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Admin view of a shop connection row. JSON keys mirror the exact column labels
 * previously returned by the raw JDBC {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminShopConnectionDtoOut {

    @JsonProperty("id")
    Object id;

    @JsonProperty("partner_app_id")
    Object partnerAppId;

    @JsonProperty("platform")
    String platform;

    @JsonProperty("shop_handle")
    String shopHandle;

    @JsonProperty("active")
    Boolean active;

    @JsonProperty("created_at")
    Instant createdAt;
}
