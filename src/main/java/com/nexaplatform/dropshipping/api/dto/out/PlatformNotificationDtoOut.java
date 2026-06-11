package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * View of an in-app notification. {@code payload} is a free-form JSON blob
 * mirroring the entity's JSON column, so it stays a Map by design.
 */
@Value
@Builder
public class PlatformNotificationDtoOut {

    UUID id;
    String eventType;
    String title;
    String body;
    String channel;
    Map<String, Object> payload;
    Instant readAt;
    Instant createdAt;
}
