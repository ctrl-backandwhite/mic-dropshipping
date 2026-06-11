package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.WebhookSubscriptionCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WebhookSubscriptionDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminWebhookMapper;
import com.nexaplatform.dropshipping.application.service.AdminWebhookService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWebhookServiceTest {

    @Mock WebhookSubscriptionRepository subscriptionRepository;
    @Mock WebhookDeliveryRepository deliveryRepository;
    @Mock WebhookDispatcherService dispatcher;
    @Mock AdminWebhookMapper mapper;

    @Captor ArgumentCaptor<WebhookSubscriptionEntity> subCaptor;

    private AdminWebhookService service() {
        return new AdminWebhookService(subscriptionRepository, deliveryRepository, dispatcher, mapper);
    }

    @Test
    void create_generatesSecretAndPersistsActiveSubscription() {
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toSubscriptionDto(any())).thenReturn(WebhookSubscriptionDtoOut.builder().build());
        var req = new WebhookSubscriptionCreateDtoIn("orders", "https://x.test/hook", null, "desc");

        service().create(req);

        verify(subscriptionRepository).save(subCaptor.capture());
        WebhookSubscriptionEntity saved = subCaptor.getValue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getEvents()).isEmpty();
        assertThat(saved.getSecret()).startsWith("whsec_");
    }

    @Test
    void fireTest_publishesTestAndReturnsSubscription() {
        UUID id = UUID.randomUUID();
        var sub = WebhookSubscriptionEntity.builder().name("orders").build();
        when(subscriptionRepository.findById(id)).thenReturn(Optional.of(sub));
        when(mapper.toSubscriptionDto(sub)).thenReturn(WebhookSubscriptionDtoOut.builder().build());

        service().fireTest(id);

        verify(dispatcher).publishTest(sub);
    }

    @Test
    void fireTest_throwsWhenSubscriptionMissing() {
        UUID id = UUID.randomUUID();
        when(subscriptionRepository.findById(id)).thenReturn(Optional.empty());
        AdminWebhookService svc = service();

        assertThatThrownBy(() -> svc.fireTest(id))
                .isInstanceOf(NotFoundException.class);
    }
}
