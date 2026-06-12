package com.nexaplatform.dropshipping.domain.model;

import lombok.Builder;
import lombok.Data;

/** Result of creating/rotating an OAuth2 client: the plaintext secret is returned only once. */
@Data
@Builder
public class AdminOAuthClientCreated {
    private String id;
    private String clientId;
    private String clientSecret;
    private String name;
}
