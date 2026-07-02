package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketReplyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Mensajes de un hilo de soporte, en orden cronológico. */
public interface SupportTicketReplyJpaRepository extends JpaRepository<SupportTicketReplyEntity, UUID> {

    List<SupportTicketReplyEntity> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
