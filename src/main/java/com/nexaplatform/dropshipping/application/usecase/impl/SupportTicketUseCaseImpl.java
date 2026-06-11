package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.SupportTicketUseCase;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
