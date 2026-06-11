package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * View of a support ticket.
 */
@Value
@Builder
public class SupportTicketDtoOut {

    UUID id;
    String kind;
    String subject;
    String body;
    UUID orderId;
    String status;
    String priority;
    String resolution;
    Instant createdAt;
}
