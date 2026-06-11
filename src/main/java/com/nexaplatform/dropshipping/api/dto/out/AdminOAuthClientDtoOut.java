package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/**
 * Admin view of an OAuth2 registered client row. JSON keys mirror the exact
 * column labels previously returned by the raw JDBC {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminOAuthClientDtoOut {

    @JsonProperty("id")
    String id;

    @JsonProperty("client_id")
    String clientId;

    @JsonProperty("client_name")
    String clientName;

    @JsonProperty("auth_methods")
    String authMethods;

    @JsonProperty("grant_types")
    String grantTypes;

    @JsonProperty("redirect_uris")
    String redirectUris;

    @JsonProperty("scopes")
    String scopes;
}
