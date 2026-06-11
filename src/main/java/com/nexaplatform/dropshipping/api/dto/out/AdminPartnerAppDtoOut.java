package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Admin view of a partner app row. JSON keys mirror the exact column labels
 * previously returned by the raw JDBC {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminPartnerAppDtoOut {

    @JsonProperty("id")
    Object id;

    @JsonProperty("name")
    String name;

    @JsonProperty("description")
    String description;

    @JsonProperty("client_id")
    String clientId;

    @JsonProperty("scopes")
    String scopes;

    @JsonProperty("webhook_url")
    String webhookUrl;

    @JsonProperty("active")
    Boolean active;

    @JsonProperty("created_at")
    Instant createdAt;
}
