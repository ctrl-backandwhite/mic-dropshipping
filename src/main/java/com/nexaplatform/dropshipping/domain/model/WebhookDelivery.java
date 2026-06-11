package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure domain model for a webhook delivery attempt (secondary/nested aggregate
 * of {@link WebhookSubscription}). Carries the read-only fields surfaced by the
 * admin deliveries view.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookDelivery {

    private UUID id;
    private String eventType;
    private String eventId;
    private String status;
    private int attempt;
    private Integer responseStatus;
    private String responseBody;
    private Instant lastAttemptAt;
    private Instant nextRetryAt;
    private Instant createdAt;
}
