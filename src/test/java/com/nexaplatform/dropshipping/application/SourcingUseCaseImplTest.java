package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.SourcingUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import com.nexaplatform.dropshipping.domain.repository.SourcingAgentRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingQuoteRepository;
import com.nexaplatform.dropshipping.domain.repository.SourcingRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourcingUseCaseImplTest {

    @Mock SourcingRequestRepository sourcingRequestRepository;
    @Mock SourcingQuoteRepository sourcingQuoteRepository;
    @Mock SourcingAgentRepository sourcingAgentRepository;
    @InjectMocks SourcingUseCaseImpl useCase;

    @Test
    void create_persistsRequestWithDetectedSourceAndQuotesCount() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(0L);
        when(sourcingRequestRepository.save(any(SourcingRequest.class)))
                .thenAnswer(inv -> ((SourcingRequest) inv.getArgument(0)).withId(UUID.randomUUID()));
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(any())).thenReturn(List.of());

        SourcingRequest created = useCase.create(userId, "https://www.1688.com/x", "hint", "notes");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getSource()).isEqualTo("1688");
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getQuotesCount()).isZero();
        verify(sourcingRequestRepository).save(any(SourcingRequest.class));
    }

    @Test
    void create_throwsWhenQuotaReached() {
        UUID userId = UUID.randomUUID();
        when(sourcingRequestRepository.countByUserIdAndCreatedAtAfter(eq(userId), any(Instant.class))).thenReturn(5L);

        assertThatThrownBy(() -> useCase.create(userId, "https://taobao.com/x", null, null))
                .isInstanceOf(BusinessException.class);
        verify(sourcingRequestRepository, never()).save(any());
    }

    @Test
    void detail_throwsNotFoundWhenNotOwner() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id))
                .thenReturn(SourcingRequest.builder().id(id).userId(owner).build());

        assertThatThrownBy(() -> useCase.detail(other, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_throwsWhenRequestHasAcceptedQuote() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id))
                .thenReturn(SourcingRequest.builder().id(id).userId(userId).build());
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id))
                .thenReturn(List.of(SourcingQuote.builder().status("ACCEPTED").build()));

        assertThatThrownBy(() -> useCase.delete(userId, id)).isInstanceOf(BusinessException.class);
        verify(sourcingRequestRepository, never()).delete(any());
    }

    @Test
    void selectQuote_marksQuoteAcceptedAndRequestApproved() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        SourcingRequest req = SourcingRequest.builder().id(id).userId(userId).status("QUOTING").build();
        SourcingQuote quote = SourcingQuote.builder().id(quoteId).requestId(id).status("OPEN").build();
        when(sourcingRequestRepository.getById(id)).thenReturn(req);
        when(sourcingQuoteRepository.getById(quoteId)).thenReturn(quote);
        when(sourcingRequestRepository.save(req)).thenReturn(req);
        when(sourcingQuoteRepository.findByRequestIdOrderByPriceUsdCentsAsc(id)).thenReturn(List.of(quote));

        SourcingRequest result = useCase.selectQuote(userId, id, quoteId);

        assertThat(quote.getStatus()).isEqualTo("ACCEPTED");
        assertThat(result.getStatus()).isEqualTo("APPROVED");
        assertThat(result.getSelectedQuoteId()).isEqualTo(quoteId);
        verify(sourcingQuoteRepository).save(quote);
        verify(sourcingRequestRepository).save(req);
    }

    @Test
    void selectQuote_throwsWhenQuoteBelongsToAnotherRequest() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        when(sourcingRequestRepository.getById(id))
                .thenReturn(SourcingRequest.builder().id(id).userId(userId).build());
        when(sourcingQuoteRepository.getById(quoteId))
                .thenReturn(SourcingQuote.builder().id(quoteId).requestId(UUID.randomUUID()).build());

        assertThatThrownBy(() -> useCase.selectQuote(userId, id, quoteId)).isInstanceOf(BusinessException.class);
    }
}
