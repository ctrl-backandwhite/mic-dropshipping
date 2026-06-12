package com.nexaplatform.dropshipping.infrastructure.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NewsletterSubscriberEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NewsletterSubscriberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka → email bridge for the monolith (the order/affiliate/newsletter events used to be
 * consumed by an external notification service). Renders the branded Thymeleaf template and
 * enqueues the email via {@link EmailQueueService}; marketing emails respect the user opt-out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatchConsumer {

    private final ObjectMapper objectMapper;
    private final EmailQueueService emailQueue;
    private final UserRepository userRepository;
    private final NewsletterSubscriberRepository subscriberRepository;

    @Value("${nexadrop.storefront.base-url:http://localhost:3003}")
    private String baseUrl;

    /** Single transactional / event notification → one branded email. */
    @KafkaListener(topics = NexaTopics.NOTIFICATIONS_DISPATCH, groupId = "nexadrop-email-dispatch")
    public void onDispatch(ConsumerRecord<String, Object> record) {
        try {
            JsonNode n = objectMapper.valueToTree(record.value());
            String email = text(n, "userEmail");
            String title = text(n, "title");
            if (email == null || email.isBlank() || title == null || title.isBlank()) {
                return; // not an email-bearing notification
            }
            boolean marketing = n.path("marketing").asBoolean(false);
            if (marketing && isOptedOut(email)) {
                log.debug("Skipping marketing email to {} (opted out)", email);
                return;
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("body", text(n, "body"));
            vars.put("bodyHtml", text(n, "bodyHtml"));
            vars.put("ctaUrl", absolute(text(n, "ctaUrl")));
            vars.put("ctaLabel", text(n, "ctaLabel"));
            vars.put("preheader", text(n, "preheader"));
            emailQueue.enqueue(email, title, "emails/notification", vars);
        } catch (RuntimeException e) {
            log.warn("email dispatch failed: {}", e.getMessage());
        }
    }

    /** Newsletter fan-out: one event → an email per subscribed recipient. */
    @KafkaListener(topics = NexaTopics.NEWSLETTER_SEND, groupId = "nexadrop-newsletter")
    public void onNewsletter(ConsumerRecord<String, Object> record) {
        try {
            JsonNode n = objectMapper.valueToTree(record.value());
            String subject = text(n, "subject");
            String bodyHtml = text(n, "bodyHtml");
            if (subject == null || bodyHtml == null) {
                return;
            }
            int sent = 0;
            for (NewsletterSubscriberEntity sub : subscriberRepository.findByStatus("SUBSCRIBED")) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("title", subject);
                vars.put("bodyHtml", bodyHtml);
                vars.put("unsubscribeUrl", baseUrl + "/newsletter/unsubscribe?token=" + sub.getToken());
                vars.put("footerNote", "Recibes este correo porque te suscribiste al boletín de NX036.");
                emailQueue.enqueue(sub.getEmail(), subject, "emails/notification", vars);
                sent++;
            }
            log.info("::> [NEWSLETTER] queued {} emails", sent);
        } catch (RuntimeException e) {
            log.warn("newsletter dispatch failed: {}", e.getMessage());
        }
    }

    private boolean isOptedOut(String email) {
        return userRepository.findByEmail(email).map(u -> u.isMarketingOptOut()).orElse(false);
    }

    private String absolute(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.startsWith("http") ? url : baseUrl + (url.startsWith("/") ? url : "/" + url);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
