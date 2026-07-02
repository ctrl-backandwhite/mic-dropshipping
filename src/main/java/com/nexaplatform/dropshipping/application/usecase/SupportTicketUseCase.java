package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.model.SupportTicketReply;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the support-ticket aggregate (DROP-11). Operates on the
 * {@link SupportTicket} domain model; user operations are scoped to the owning
 * {@code userId}.
 */
public interface SupportTicketUseCase {

    /** Opens a ticket for the user. */
    SupportTicket open(UUID userId, SupportTicket model);

    /** Lists the user's tickets, newest first. */
    List<SupportTicket> myTickets(UUID userId);

    /** Lists all tickets (admin), optionally filtered by status. */
    List<SupportTicket> adminList(String status);

    /** Resolves a ticket (admin), recording the resolution text. */
    SupportTicket resolve(UUID id, String resolution);

    /** Mensajes del hilo de un ticket (acceso: el dueño o un admin). */
    List<SupportTicketReply> listReplies(UUID ticketId, UUID requesterId, boolean isAdmin);

    /** Añade un mensaje al hilo y notifica a la otra parte (usuario↔admin). */
    SupportTicketReply addReply(UUID ticketId, UUID authorId, boolean isAdmin, String body);
}
