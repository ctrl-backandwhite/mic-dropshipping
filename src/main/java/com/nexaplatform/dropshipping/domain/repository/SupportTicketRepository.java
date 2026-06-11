package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.SupportTicket;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link SupportTicket}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface SupportTicketRepository extends BaseRepository<SupportTicket, SupportTicket, UUID> {

    /** Lists the tickets owned by a user, newest first. */
    List<SupportTicket> findByUserId(UUID userId);

    /** Lists the tickets in a given status, newest first. */
    List<SupportTicket> findByStatus(String status);
}
