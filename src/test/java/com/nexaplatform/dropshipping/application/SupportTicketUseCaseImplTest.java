package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.SupportTicketUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.SupportTicket;
import com.nexaplatform.dropshipping.domain.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportTicketUseCaseImplTest {

    @Mock SupportTicketRepository supportTicketRepository;
    @InjectMocks SupportTicketUseCaseImpl useCase;

    @Test
    void open_defaultsPriorityAndStatus() {
        UUID userId = UUID.randomUUID();
        SupportTicket incoming = SupportTicket.builder().kind("SUPPORT").subject("s").build();
        when(supportTicketRepository.save(incoming)).thenReturn(incoming.withId(UUID.randomUUID()));

        useCase.open(userId, incoming);

        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getPriority()).isEqualTo("NORMAL");
        assertThat(captor.getValue().getStatus()).isEqualTo("OPEN");
    }

    @Test
    void resolve_setsResolvedStatusAndResolution() {
        UUID id = UUID.randomUUID();
        SupportTicket existing = SupportTicket.builder().id(id).status("OPEN").build();
        when(supportTicketRepository.getById(id)).thenReturn(existing);
        when(supportTicketRepository.update(existing)).thenReturn(existing);

        useCase.resolve(id, "done");

        assertThat(existing.getStatus()).isEqualTo("RESOLVED");
        assertThat(existing.getResolution()).isEqualTo("done");
        verify(supportTicketRepository).update(existing);
    }

    @Test
    void resolve_throwsWhenTicketNotFound() {
        UUID id = UUID.randomUUID();
        when(supportTicketRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.resolve(id, "x")).isInstanceOf(NotFoundException.class);
    }
}
