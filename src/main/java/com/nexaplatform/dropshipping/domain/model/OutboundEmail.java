package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Secondary domain model of the Auth/User cluster: a queued outbound email
 * (welcome / password-reset notifications). Mirrors the {@code OutboundEmailEntity};
 * delivery stays in the infrastructure email queue collaborator.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboundEmail {

    private UUID id;
    private String toAddress;
    private String subject;
    private String bodyHtml;
    private String template;
    private String status;
    private int attemptCount;
    private Instant sentAt;
    private String errorMessage;
    private Instant createdAt;
}
