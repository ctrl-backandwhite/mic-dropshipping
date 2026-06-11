package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;

/**
 * Pure domain projection for a partner webhook delivery row. Read-only model
 * aggregated by the Admin Partners use case from the
 * {@code partner_webhook_delivery} table; the api mapper translates it to
 * {@code AdminPartnerWebhookDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPartnerWebhook {

    private Object id;
    private Object partnerAppId;
    private String eventType;
    private String status;
    private Integer attemptCount;
    private Integer responseCode;
    private Instant createdAt;
}
