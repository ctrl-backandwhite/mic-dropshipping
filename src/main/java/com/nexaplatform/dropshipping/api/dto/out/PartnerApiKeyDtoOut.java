package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Listed view of a partner API key (no secret). Mirrors the previous
 * {@code ListedView} record returned by the controller.
 */
@Value
@Builder
public class PartnerApiKeyDtoOut {

    String clientId;
    String name;
    List<String> scopes;
    Instant createdAt;
    String plan;
}
