package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin view of a webhook subscription.
 */
@Value
@Builder
public class WebhookSubscriptionDtoOut {

    UUID id;
    String name;
    String targetUrl;
    String secret;
    List<String> events;
    boolean active;
    String description;
    Instant createdAt;
}
