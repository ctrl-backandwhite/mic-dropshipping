package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a webhook delivery attempt.
 */
@Value
@Builder
public class WebhookDeliveryDtoOut {

    UUID id;
    String eventType;
    String eventId;
    String status;
    int attempt;
    Integer responseStatus;
    String responseBody;
    Instant lastAttemptAt;
    Instant nextRetryAt;
    Instant createdAt;
}
