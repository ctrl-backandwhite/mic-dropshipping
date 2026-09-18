package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.NewsletterService;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterCampaignEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterSubscriberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterCampaignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterSubscriberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsletterServiceTest {

    @Mock
    NewsletterSubscriberRepository subscriberRepo;
    @Mock
    NewsletterCampaignRepository campaignRepo;
    @Mock
    EventPublisher eventPublisher;
    @Mock
    EmailQueueService emailQueueService;
    @InjectMocks
    NewsletterService service;

    @Test
    void subscribe_rejectsNullOrBlankEmail() {
        assertThatThrownBy(() -> service.subscribe(null, null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.subscribe("  ", null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(subscriberRepo);
    }

    @Test
    void subscribe_normalizesEmailAndCreatesNewSubscriber() {
        when(subscriberRepo.findByEmailIgnoreCase("user@mail.com")).thenReturn(Optional.empty());
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID userId = UUID.randomUUID();

        NewsletterService.SubscribeResult result = service.subscribe("  USER@Mail.com  ", userId, "footer");

        // Doble opt-in: el alta nace PENDIENTE. Sólo el clic en el enlace que llega a ese buzón la
        // confirma, y ese clic es la prueba de que quien consiente tiene acceso al correo.
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.alreadySubscribed()).isFalse();
        ArgumentCaptor<NewsletterSubscriberEntity> cap = ArgumentCaptor.forClass(NewsletterSubscriberEntity.class);
        verify(subscriberRepo).save(cap.capture());
        NewsletterSubscriberEntity saved = cap.getValue();
        assertThat(saved.getEmail()).isEqualTo("user@mail.com");
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getSource()).isEqualTo("footer");
        assertThat(saved.getToken()).isNotBlank().doesNotContain("-");
    }

    @Test
    void subscribe_defaultsSourceToStorefrontWhenNull() {
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.empty());
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.subscribe("a@b.com", null, null);

        ArgumentCaptor<NewsletterSubscriberEntity> cap = ArgumentCaptor.forClass(NewsletterSubscriberEntity.class);
        verify(subscriberRepo).save(cap.capture());
        assertThat(cap.getValue().getSource()).isEqualTo("storefront");
    }

    @Test
    void subscribe_reactivatesExistingSubscriberIdempotently() {
        NewsletterSubscriberEntity existing = NewsletterSubscriberEntity.builder().email("a@b.com")
                .status("UNSUBSCRIBED").token("tok").source("storefront").build();
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(existing));
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID userId = UUID.randomUUID();

        NewsletterService.SubscribeResult result = service.subscribe("a@b.com", userId, "ignored");

        // Estaba UNSUBSCRIBED → no contaba como "ya suscrito"; se reactiva sobre la MISMA fila (idempotente).
        assertThat(result.alreadySubscribed()).isFalse();
        ArgumentCaptor<NewsletterSubscriberEntity> cap = ArgumentCaptor.forClass(NewsletterSubscriberEntity.class);
        verify(subscriberRepo).save(cap.capture());
        NewsletterSubscriberEntity saved = cap.getValue();
        assertThat(saved).isSameAs(existing);
        // Reactivar tampoco salta la confirmación: vuelve a PENDING hasta que se pulse el enlace.
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getUserId()).isEqualTo(userId);
        // No se sobreescribe el source ni se genera token nuevo en el camino de reactivación.
        assertThat(saved.getSource()).isEqualTo("storefront");
        assertThat(saved.getToken()).isEqualTo("tok");
    }

    @Test
    void unsubscribe_marksSubscriberAndReturnsTrue() {
        NewsletterSubscriberEntity sub = NewsletterSubscriberEntity.builder().email("a@b.com").status("SUBSCRIBED")
                .token("tok").build();
        when(subscriberRepo.findByToken("tok")).thenReturn(Optional.of(sub));

        boolean result = service.unsubscribe("tok");

        assertThat(result).isTrue();
        assertThat(sub.getStatus()).isEqualTo("UNSUBSCRIBED");
        verify(subscriberRepo).save(sub);
    }

    @Test
    void unsubscribe_returnsFalseWhenTokenUnknown() {
        when(subscriberRepo.findByToken("missing")).thenReturn(Optional.empty());

        assertThat(service.unsubscribe("missing")).isFalse();
        verify(subscriberRepo, never()).save(any());
    }

    @Test
    void subscriberCount_delegatesToRepository() {
        when(subscriberRepo.countByStatus("SUBSCRIBED")).thenReturn(7L);
        assertThat(service.subscriberCount()).isEqualTo(7L);
    }

    @Test
    void send_rejectsBlankSubjectOrBody() {
        assertThatThrownBy(() -> service.send(" ", "<p>hi</p>")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send("Hi", " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(campaignRepo, eventPublisher);
    }

    @Test
    void send_persistsCampaignAndPublishesFanOutEvent() {
        UUID campaignId = UUID.randomUUID();
        when(subscriberRepo.countByStatus("SUBSCRIBED")).thenReturn(3L);
        when(campaignRepo.save(any(NewsletterCampaignEntity.class))).thenAnswer(inv -> {
            NewsletterCampaignEntity c = inv.getArgument(0);
            c.setId(campaignId);
            return c;
        });

        NewsletterCampaignEntity campaign = service.send("Hello", "<p>body</p>");

        assertThat(campaign.getSubject()).isEqualTo("Hello");
        assertThat(campaign.getBodyHtml()).isEqualTo("<p>body</p>");
        assertThat(campaign.getRecipients()).isEqualTo(3);
        assertThat(campaign.getStatus()).isEqualTo("SENT");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(eventPublisher).publish(eq(NexaTopics.NEWSLETTER_SEND), eq("Newsletter"),
                eq(campaignId.toString()), eq(campaignId.toString()), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("subject", "Hello")
                .containsEntry("bodyHtml", "<p>body</p>")
                .containsEntry("campaignId", campaignId.toString());
    }

    @Test
    void subscribe_sendsTheConfirmationEmail() {
        // Sin este correo no hay doble opt-in: el alta se quedaría pendiente para siempre y el usuario
        // creería estar suscrito.
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.empty());
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.subscribe("a@b.com", null, null);

        verify(emailQueueService).enqueue(eq("a@b.com"), anyString(), anyString(), any());
    }

    @Test
    void subscribe_yaConfirmadoNoReenviaNiDegradaElEstado() {
        // Quien ya confirmó no puede volver a PENDING porque otro escriba su correo en el formulario:
        // sería una forma trivial de darle de baja a traición.
        NewsletterSubscriberEntity ya = NewsletterSubscriberEntity.builder().email("a@b.com")
                .status("SUBSCRIBED").token("tok").build();
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(ya));

        NewsletterService.SubscribeResult r = service.subscribe("a@b.com", null, null);

        assertThat(r.alreadySubscribed()).isTrue();
        assertThat(ya.getStatus()).isEqualTo("SUBSCRIBED");
        verifyNoInteractions(emailQueueService);
    }

    @Test
    void confirm_activaLaSuscripcionYDejaConstanciaDeCuando() {
        NewsletterSubscriberEntity pendiente = NewsletterSubscriberEntity.builder().email("a@b.com")
                .status("PENDING").token("tok").build();
        when(subscriberRepo.findByToken("tok")).thenReturn(Optional.of(pendiente));

        assertThat(service.confirm("tok")).isTrue();

        assertThat(pendiente.getStatus()).isEqualTo("SUBSCRIBED");
        // La fecha es la prueba de cuándo se prestó el consentimiento.
        assertThat(pendiente.getConfirmedAt()).isNotNull();
    }

    @Test
    void confirm_conUnTestigoDesconocidoNoSuscribeANadie() {
        when(subscriberRepo.findByToken("inventado")).thenReturn(Optional.empty());

        assertThat(service.confirm("inventado")).isFalse();
        verify(subscriberRepo, never()).save(any());
    }
}
