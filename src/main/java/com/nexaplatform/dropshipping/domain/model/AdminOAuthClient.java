package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Pure domain projection for an OAuth2 registered client row. Read-only model
 * aggregated by the Admin Partners use case from the {@code oauth2_registered_client}
 * table; the api mapper translates it to {@code AdminOAuthClientDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOAuthClient {

    private String id;
    private String clientId;
    private String clientName;
    private String authMethods;
    private String grantTypes;
    private String redirectUris;
    private String scopes;
}
