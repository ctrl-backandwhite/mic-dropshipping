package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * One-time view returned right after creating a partner API key. The
 * {@code clientSecret} is exposed exactly once and never persisted in clear.
 */
@Value
@Builder
public class PartnerApiKeyCreatedDtoOut {

    String clientId;
    String clientSecret;
    String name;
    List<String> scopes;
    Instant createdAt;
    String message;
}
