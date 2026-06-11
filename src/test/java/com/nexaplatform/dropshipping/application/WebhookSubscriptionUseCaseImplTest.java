package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.WebhookSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.impl.WebhookSubscriptionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.WebhookSubscription;
import com.nexaplatform.dropshipping.domain.repository.WebhookSubscriptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WebhookSubscriptionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookSubscriptionUseCaseImplTest {

    @Mock WebhookSubscriptionRepository webhookSubscriptionRepository;
    @Mock WebhookSubscriptionUpdateMapper webhookSubscriptionUpdateMapper;
    @Mock WebhookSubscriptionEntityMapper webhookSubscriptionEntityMapper;
    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock WebhookDispatcherService dispatcher;
    @InjectMocks WebhookSubscriptionUseCaseImpl useCase;

    @Test
    void save_generatesSecretActivatesAndPersists() {
        WebhookSubscription model = WebhookSubscription.builder().name("orders").build();
        when(webhookSubscriptionRepository.save(any())).thenAnswer(inv -> {
            WebhookSubscription arg = inv.getArgument(0);
            return arg.withId(UUID.randomUUID());
        });

        WebhookSubscription saved = useCase.save(model);

        assertThat(saved.getId()).isNotNull();
        assertThat(model.getSecret()).startsWith("whsec_");
        assertThat(model.isActive()).isTrue();
        verify(webhookSubscriptionRepository).save(model);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(webhookSubscriptionRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void update_appliesPartialUpdateAndPersists() {
        UUID id = UUID.randomUUID();
        WebhookSubscription existing = WebhookSubscription.builder().id(id).name("old").build();
        WebhookSubscription incoming = WebhookSubscription.builder().name("new").build();
        when(webhookSubscriptionRepository.getById(id)).thenReturn(existing);
        when(webhookSubscriptionRepository.update(existing)).thenReturn(existing);

        useCase.update(incoming, id);

        verify(webhookSubscriptionUpdateMapper).updateFromModel(incoming, existing);
        verify(webhookSubscriptionRepository).update(existing);
    }

    @Test
    void rotate_regeneratesSecretAndPersists() {
        UUID id = UUID.randomUUID();
        WebhookSubscription existing = WebhookSubscription.builder().id(id).secret("whsec_old").build();
        when(webhookSubscriptionRepository.getById(id)).thenReturn(existing);
        when(webhookSubscriptionRepository.update(existing)).thenReturn(existing);

        useCase.rotate(id);

        assertThat(existing.getSecret()).startsWith("whsec_");
        assertThat(existing.getSecret()).isNotEqualTo("whsec_old");
        verify(webhookSubscriptionRepository).update(existing);
    }

    @Test
    void fireTest_publishesTestAndReturnsSubscription() {
        UUID id = UUID.randomUUID();
        WebhookSubscription existing = WebhookSubscription.builder().id(id).name("orders").build();
        when(webhookSubscriptionRepository.getById(id)).thenReturn(existing);

        WebhookSubscription result = useCase.fireTest(id);

        assertThat(result).isSameAs(existing);
        verify(dispatcher).publishTest(id);
    }

    @Test
    void fireTest_throwsWhenSubscriptionMissing() {
        UUID id = UUID.randomUUID();
        when(webhookSubscriptionRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.fireTest(id)).isInstanceOf(NotFoundException.class);
    }
}
