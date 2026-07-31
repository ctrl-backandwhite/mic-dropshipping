package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.outbox.EventPublisher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterCampaignEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterSubscriberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterCampaignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterSubscriberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Newsletter: subscribe / unsubscribe and admin send. Sending publishes a single
 * {@code newsletter.send} event to Kafka; the {@code EmailDispatchConsumer} fans it out to all
 * subscribed recipients with the branded template (DROP email/newsletter feature).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsletterService {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String SUBSCRIBED = "SUBSCRIBED";

    private final NewsletterSubscriberRepository subscriberRepo;
    private final NewsletterCampaignRepository campaignRepo;
    private final EventPublisher eventPublisher;

    /** Resultado de suscribir: el estado final y si el correo YA estaba suscrito (para el aviso al usuario). */
    public record SubscribeResult(String status, boolean alreadySubscribed) {
    }

    @Transactional
    public SubscribeResult subscribe(String email, UUID userId, String source) {
        if (email == null || email.isBlank()) {
            throw new BusinessException("Email obligatorio");
        }
        String normalized = email.trim().toLowerCase();
        NewsletterSubscriberEntity sub = subscriberRepo.findByEmailIgnoreCase(normalized).orElse(null);
        // Idempotente: un mismo correo NUNCA crea filas duplicadas (además del UNIQUE(email) en BD). Si ya
        // estaba suscrito, lo indicamos para que el front muestre "ya estabas suscrito" en vez de "gracias".
        boolean alreadySubscribed = sub != null && SUBSCRIBED.equals(sub.getStatus());
        if (sub == null) {
            sub = NewsletterSubscriberEntity.builder().email(normalized).userId(userId).status(SUBSCRIBED)
                    .token(UUID.randomUUID().toString().replace("-", "")).source(source != null ? source : "storefront")
                    .build();
        } else {
            sub.setStatus(SUBSCRIBED);
            if (userId != null) {
                sub.setUserId(userId);
            }
        }
        NewsletterSubscriberEntity saved = subscriberRepo.save(sub);
        return new SubscribeResult(saved.getStatus(), alreadySubscribed);
    }

    @Transactional
    public boolean unsubscribe(String token) {
        return subscriberRepo.findByToken(token).map(s -> {
            s.setStatus("UNSUBSCRIBED");
            subscriberRepo.save(s);
            return true;
        }).orElse(false);
    }

    @Transactional(readOnly = true)
    public long subscriberCount() {
        return subscriberRepo.countByStatus(SUBSCRIBED);
    }

    @Transactional(readOnly = true)
    public List<NewsletterCampaignEntity> recentCampaigns() {
        return campaignRepo.findTop20ByOrderByCreatedAtDesc();
    }

    /** Admin send: record the campaign and publish the fan-out event to Kafka. */
    @Transactional
    public NewsletterCampaignEntity send(String subject, String bodyHtml) {
        if (subject == null || subject.isBlank() || bodyHtml == null || bodyHtml.isBlank()) {
            throw new BusinessException("Asunto y contenido obligatorios");
        }
        int recipients = (int) subscriberRepo.countByStatus(SUBSCRIBED);
        NewsletterCampaignEntity campaign = campaignRepo.save(NewsletterCampaignEntity.builder().subject(subject)
                .bodyHtml(bodyHtml).recipients(recipients).status("SENT").build());
        eventPublisher.publish(NexaTopics.NEWSLETTER_SEND, "Newsletter", campaign.getId().toString(),
                campaign.getId().toString(),
                Map.of("subject", subject, "bodyHtml", bodyHtml, "campaignId", campaign.getId().toString()));
        log.info("::> [NEWSLETTER] campaign {} → {} recipients", campaign.getId(), recipients);
        return campaign;
    }
}
