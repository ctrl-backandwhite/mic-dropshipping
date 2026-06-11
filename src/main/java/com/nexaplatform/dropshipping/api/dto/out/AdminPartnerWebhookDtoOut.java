package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Admin view of a partner webhook delivery row. JSON keys mirror the exact
 * column labels previously returned by the raw JDBC {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminPartnerWebhookDtoOut {

    @JsonProperty("id")
    Object id;

    @JsonProperty("partner_app_id")
    Object partnerAppId;

    @JsonProperty("event_type")
    String eventType;

    @JsonProperty("status")
    String status;

    @JsonProperty("attempt_count")
    Integer attemptCount;

    @JsonProperty("response_code")
    Integer responseCode;

    @JsonProperty("created_at")
    Instant createdAt;
}
