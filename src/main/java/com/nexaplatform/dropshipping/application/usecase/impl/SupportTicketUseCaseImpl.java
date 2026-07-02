package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.SupportTicketUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.model.SupportTicketReply;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.NotificationRepository;
import com.nexaplatform.dropshipping.domain.repository.SupportTicketRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketReplyEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketReplyJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Support-ticket use case (DROP-11). Operates on the {@link SupportTicket} model
 * and delegates persistence to the domain port. Holds the logic that used to live
 * in {@code PlatformExtrasService}: opening a ticket (defaulting priority/status),
 * the owner-scoped and admin listings and the admin resolution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportTicketUseCaseImpl implements SupportTicketUseCase {

    private final SupportTicketRepository supportTicketRepository;
    private final SupportTicketReplyJpaRepository replyRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public SupportTicket open(UUID userId, SupportTicket model) {
        model.setUserId(userId);
        if (model.getPriority() == null) {
            model.setPriority("NORMAL");
        }
        model.setStatus("OPEN");
        SupportTicket saved = supportTicketRepository.save(model);
        log.info("::> [SUPPORT] Ticket opened id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicket> myTickets(UUID userId) {
        return supportTicketRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicket> adminList(String status) {
        return (status == null || status.isBlank())
                ? supportTicketRepository.findAll()
                : supportTicketRepository.findByStatus(status);
    }

    @Override
    @Transactional
    public SupportTicket resolve(UUID id, String resolution) {
        SupportTicket existing = supportTicketRepository.getById(id);
        if (Objects.isNull(existing)) {
            throw new NotFoundException("Ticket");
        }
        existing.setStatus("RESOLVED");
        existing.setResolution(resolution);
        SupportTicket saved = supportTicketRepository.update(existing);
        log.info("::> [SUPPORT] Ticket resolved id={}", id);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportTicketReply> listReplies(UUID ticketId, UUID requesterId, boolean isAdmin) {
        SupportTicket ticket = requireAccess(ticketId, requesterId, isAdmin);
        UUID owner = ticket.getUserId();
        return replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(r -> new SupportTicketReply(r.getId(), ticketId, !r.getAuthorId().equals(owner), r.getBody(),
                        r.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public SupportTicketReply addReply(UUID ticketId, UUID authorId, boolean isAdmin, String body) {
        String text = body == null ? "" : body.trim();
        if (text.isBlank()) {
            throw new BusinessException("El mensaje no puede estar vacío.");
        }
        SupportTicket ticket = requireAccess(ticketId, authorId, isAdmin);
        boolean fromSupport = !authorId.equals(ticket.getUserId());
        SupportTicketReplyEntity saved = replyRepository.save(SupportTicketReplyEntity.builder().ticketId(ticketId)
                .authorId(authorId).body(text).createdAt(Instant.now()).build());
        // Si el cliente responde a un ticket resuelto, lo reabrimos.
        if (!fromSupport && "RESOLVED".equalsIgnoreCase(ticket.getStatus())) {
            ticket.setStatus("OPEN");
            supportTicketRepository.update(ticket);
        }
        notifyReply(ticket, fromSupport, text);
        log.info("::> [SUPPORT] Reply on ticket={} fromSupport={}", ticketId, fromSupport);
        return new SupportTicketReply(saved.getId(), ticketId, fromSupport, saved.getBody(), saved.getCreatedAt());
    }

    /** El dueño del ticket o cualquier admin pueden ver/escribir; el resto recibe 404 (no se filtra). */
    private SupportTicket requireAccess(UUID ticketId, UUID requesterId, boolean isAdmin) {
        SupportTicket ticket = supportTicketRepository.getById(ticketId);
        if (Objects.isNull(ticket)) {
            throw new NotFoundException("Ticket");
        }
        if (!isAdmin && !ticket.getUserId().equals(requesterId)) {
            throw new NotFoundException("Ticket");
        }
        return ticket;
    }

    /** Notifica a la otra parte: soporte→cliente dueño; cliente→todos los admins. */
    private void notifyReply(SupportTicket ticket, boolean fromSupport, String text) {
        String snippet = text.length() > 120 ? text.substring(0, 120) + "…" : text;
        if (fromSupport) {
            notificationRepository.save(PlatformNotification.builder().userId(ticket.getUserId())
                    .title("Respuesta de soporte: " + ticket.getSubject()).body(snippet).eventType("SUPPORT_REPLY")
                    .channel("IN_APP").build());
        } else {
            List<User> admins = userRepository.findAll().stream().filter(u -> u.getRole() == UserRole.ADMIN).toList();
            for (User admin : admins) {
                notificationRepository.save(PlatformNotification.builder().userId(admin.getId())
                        .title("Nuevo mensaje de soporte: " + ticket.getSubject()).body(snippet)
                        .eventType("SUPPORT_MESSAGE").channel("IN_APP").build());
            }
        }
    }
}
