package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.NewsletterService;
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

        NewsletterSubscriberEntity saved = service.subscribe("  USER@Mail.com  ", userId, "footer");

        assertThat(saved.getEmail()).isEqualTo("user@mail.com");
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo("SUBSCRIBED");
        assertThat(saved.getSource()).isEqualTo("footer");
        assertThat(saved.getToken()).isNotBlank().doesNotContain("-");
    }

    @Test
    void subscribe_defaultsSourceToStorefrontWhenNull() {
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.empty());
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        NewsletterSubscriberEntity saved = service.subscribe("a@b.com", null, null);

        assertThat(saved.getSource()).isEqualTo("storefront");
    }

    @Test
    void subscribe_reactivatesExistingSubscriberIdempotently() {
        NewsletterSubscriberEntity existing = NewsletterSubscriberEntity.builder().email("a@b.com")
                .status("UNSUBSCRIBED").token("tok").source("storefront").build();
        when(subscriberRepo.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(existing));
        when(subscriberRepo.save(any(NewsletterSubscriberEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID userId = UUID.randomUUID();

        NewsletterSubscriberEntity saved = service.subscribe("a@b.com", userId, "ignored");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getStatus()).isEqualTo("SUBSCRIBED");
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
}
