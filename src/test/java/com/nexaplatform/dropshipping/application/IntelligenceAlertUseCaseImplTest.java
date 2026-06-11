package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.usecase.impl.IntelligenceAlertUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import com.nexaplatform.dropshipping.domain.repository.IntelligenceAlertRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntelligenceAlertUseCaseImplTest {

    @Mock IntelligenceAlertRepository intelligenceAlertRepository;
    @InjectMocks IntelligenceAlertUseCaseImpl useCase;

    @Test
    void findActiveForUser_delegatesToRepository() {
        UUID userId = UUID.randomUUID();
        when(intelligenceAlertRepository.findActiveByUser(userId))
                .thenReturn(List.of(IntelligenceAlert.builder().id(UUID.randomUUID()).build()));

        List<IntelligenceAlert> result = useCase.findActiveForUser(userId);

        assertThat(result).hasSize(1);
        verify(intelligenceAlertRepository).findActiveByUser(userId);
    }

    @Test
    void create_stampsOwnerAndAppliesDefaults() {
        UUID userId = UUID.randomUUID();
        IntelligenceAlert incoming = IntelligenceAlert.builder().keyword("watch").build();
        when(intelligenceAlertRepository.save(incoming)).thenReturn(incoming.withId(UUID.randomUUID()));

        IntelligenceAlert saved = useCase.create(userId, incoming);

        ArgumentCaptor<IntelligenceAlert> captor = ArgumentCaptor.forClass(IntelligenceAlert.class);
        verify(intelligenceAlertRepository).save(captor.capture());
        IntelligenceAlert persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getChannel()).isEqualTo("EMAIL");
        assertThat(persisted.isActive()).isTrue();
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void create_keepsExplicitChannel() {
        UUID userId = UUID.randomUUID();
        IntelligenceAlert incoming = IntelligenceAlert.builder().keyword("watch").channel("SMS").build();
        when(intelligenceAlertRepository.save(incoming)).thenReturn(incoming);

        useCase.create(userId, incoming);

        assertThat(incoming.getChannel()).isEqualTo("SMS");
    }

    @Test
    void deactivate_flipsActiveAndUpdates() {
        UUID id = UUID.randomUUID();
        IntelligenceAlert existing = IntelligenceAlert.builder().id(id).active(true).build();
        when(intelligenceAlertRepository.getById(id)).thenReturn(existing);

        useCase.deactivate(id);

        assertThat(existing.isActive()).isFalse();
        verify(intelligenceAlertRepository).update(existing);
    }

    @Test
    void deactivate_silentlyIgnoresMissingAlert() {
        UUID id = UUID.randomUUID();
        when(intelligenceAlertRepository.getById(id)).thenReturn(null);

        useCase.deactivate(id);

        verify(intelligenceAlertRepository, never()).update(org.mockito.ArgumentMatchers.any());
    }
}
