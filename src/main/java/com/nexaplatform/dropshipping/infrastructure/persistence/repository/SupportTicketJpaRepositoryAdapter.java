package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code SupportTicketRepository} domain port. */
public interface SupportTicketJpaRepositoryAdapter extends JpaRepository<SupportTicketEntity, UUID> {

    List<SupportTicketEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<SupportTicketEntity> findByStatusOrderByCreatedAtDesc(String status);
}
