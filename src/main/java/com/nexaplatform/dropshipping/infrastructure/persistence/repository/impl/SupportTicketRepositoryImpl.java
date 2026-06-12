package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.repository.SupportTicketRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupportTicketEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link SupportTicketRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concerns the domain
 * model abstracts away: resolving the {@code user} and optional {@code order}
 * relations from the flattened {@code userId} / {@code orderId}, and re-applying
 * the mutable fields onto the managed entity on update. {@code findAll} preserves
 * the legacy admin-list ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class SupportTicketRepositoryImpl implements SupportTicketRepository {

    private final SupportTicketEntityMapper supportTicketEntityMapper;
    private final SupportTicketJpaRepositoryAdapter supportTicketJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    @Override
    public SupportTicket save(SupportTicket model) {
        SupportTicketEntity entity = resolveEntity(model);
        applyModel(entity, model);
        SupportTicketEntity saved = supportTicketJpaRepositoryAdapter.save(entity);
        return supportTicketEntityMapper.toDomain(saved);
    }

    @Override
    public SupportTicket update(SupportTicket model) {
        return this.save(model);
    }

    @Override
    public List<SupportTicket> findAll() {
        return supportTicketEntityMapper.toDomainList(supportTicketJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<SupportTicket> findByUserId(UUID userId) {
        return supportTicketEntityMapper
                .toDomainList(supportTicketJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public List<SupportTicket> findByStatus(String status) {
        return supportTicketEntityMapper
                .toDomainList(supportTicketJpaRepositoryAdapter.findByStatusOrderByCreatedAtDesc(status));
    }

    @Override
    public SupportTicket getById(UUID id) {
        return supportTicketJpaRepositoryAdapter.findById(id).map(supportTicketEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        supportTicketJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return supportTicketJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private SupportTicketEntity resolveEntity(SupportTicket model) {
        if (model.getId() != null) {
            return supportTicketJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Ticket"));
        }
        return new SupportTicketEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving user and optional order. */
    private void applyModel(SupportTicketEntity entity, SupportTicket model) {
        if (entity.getUser() == null) {
            entity.setUser(userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User")));
        }
        entity.setKind(model.getKind());
        entity.setSubject(model.getSubject());
        entity.setBody(model.getBody());
        // Mirror the legacy "best-effort" order lookup: a missing order id silently clears it.
        entity.setOrder(model.getOrderId() == null ? null : orderRepository.findById(model.getOrderId()).orElse(null));
        if (model.getStatus() != null) {
            entity.setStatus(model.getStatus());
        }
        if (model.getPriority() != null) {
            entity.setPriority(model.getPriority());
        }
        entity.setResolution(model.getResolution());
    }
}
