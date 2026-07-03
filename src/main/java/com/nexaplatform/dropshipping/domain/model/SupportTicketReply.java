package com.nexaplatform.dropshipping.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensaje de un hilo de soporte. {@code fromSupport} indica el lado: true = escrito por soporte/admin,
 * false = escrito por el cliente dueño del ticket.
 */
public record SupportTicketReply(UUID id, UUID ticketId, boolean fromSupport, String body, Instant createdAt) {
}
