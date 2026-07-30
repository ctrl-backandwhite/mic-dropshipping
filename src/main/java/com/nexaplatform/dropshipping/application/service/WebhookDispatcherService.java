package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookDeliveryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WebhookSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookDeliveryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WebhookSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fans out platform events to every active webhook subscription that matches the event type.
 * Payloads are HMAC-SHA256-signed with the subscriber's secret and posted asynchronously;
 * failures are queued for retry with exponential backoff (1m, 5m, 30m, 2h, 8h) up to 5 attempts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcherService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_SECONDS = {60, 300, 1800, 7200, 28800};

    // Auto-referencia POR EL PROXY: attempt() es @Async y llamarlo con this lo ejecutaba en el hilo que
    // publica el evento —síncrono y bloqueante, justo lo contrario del "fire-and-forget" que promete—.
    // La autoinvocación no pasa por el proxy, así que ni @Async ni @Transactional se aplicaban.
    @Autowired
    @Lazy
    private WebhookDispatcherService self;

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    /** DROP-663: the same lifecycle events are also delivered to active partner apps. */
    private final PartnerWebhookDispatcherService partnerWebhooks;
    /** Local mapper with JavaTimeModule so payloads carrying Instant/LocalDate serialize cleanly. */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Publish an event to every active subscription that listens to {@code eventType}. */
    @Transactional
    public void publish(String eventType, String eventId, Map<String, Object> data) {
        // DROP-663: fan the event out to active partner apps too (recorded in partner_webhook_delivery).
        partnerWebhooks.publish(eventType, eventId, data);
        List<WebhookSubscriptionEntity> subs = subscriptionRepository.findByActiveTrue();
        if (subs.isEmpty())
            return;

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("createdAt", Instant.now().toString());
        envelope.put("data", data);

        for (WebhookSubscriptionEntity s : subs) {
            if (!matches(s, eventType))
                continue;
            queue(s, eventType, eventId, envelope);
        }
    }

    /**
     * Fire a synthetic {@code test.ping} event targeted at a single subscription,
     * used by the admin "test" action. Mirrors the envelope used by {@link #publish}.
     */
    @Transactional
    public void publishTest(WebhookSubscriptionEntity subscription) {
        publishTest(subscription.getId());
    }

    /**
     * Id-based overload used by the hexagonal webhook use case so it can dispatch
     * a test without leaking JPA entities into the application layer. Fires the
     * same synthetic {@code test.ping} event as {@link #publishTest(WebhookSubscriptionEntity)}.
     */
    @Transactional
    public void publishTest(UUID subscriptionId) {
        publish("test.ping", "test-" + UUID.randomUUID(), Map.of("message", "NX036 webhook test event",
                "subscriptionId", subscriptionId.toString(), "at", Instant.now().toString()));
    }

    private static boolean matches(WebhookSubscriptionEntity s, String eventType) {
        List<String> events = s.getEvents();
        if (events == null || events.isEmpty())
            return true; // empty filter = receive all
        return events.contains(eventType) || events.contains("*");
    }

    private void queue(WebhookSubscriptionEntity s, String eventType, String eventId, Map<String, Object> envelope) {
        try {
            String body = objectMapper.writeValueAsString(envelope);
            String signature = sign(body, s.getSecret());
            WebhookDeliveryEntity d = deliveryRepository.save(WebhookDeliveryEntity.builder().subscription(s)
                    .eventType(eventType).eventId(eventId).payload(envelope).signature(signature)
                    .targetUrl(s.getTargetUrl()).status("PENDING").attempt(0).nextRetryAt(Instant.now()).build());
            // Fire-and-forget; the scheduler also picks up PENDING/RETRY rows so we never lose a delivery.
            self.attempt(d.getId());
        } catch (Exception e) {
            log.warn("Failed to queue webhook delivery for {}: {}", s.getTargetUrl(), e.getMessage());
        }
    }

    @Async
    @Transactional
    public void attempt(UUID deliveryId) {
        WebhookDeliveryEntity d = deliveryRepository.findById(deliveryId).orElse(null);
        if (d == null || "SUCCESS".equals(d.getStatus()))
            return;
        d.setLastAttemptAt(Instant.now());
        d.setAttempt(d.getAttempt() + 1);

        try {
            String body = objectMapper.writeValueAsString(d.getPayload());
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(d.getTargetUrl())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-NX036-Signature", d.getSignature())
                    .header("X-NX036-Event", d.getEventType()).header("X-NX036-Event-Id", d.getEventId())
                    .header("X-NX036-Attempt", String.valueOf(d.getAttempt()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            d.setResponseStatus(res.statusCode());
            d.setResponseBody(truncate(res.body(), 2000));
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                d.setStatus("SUCCESS");
                d.setNextRetryAt(null);
            } else {
                scheduleRetry(d);
            }
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            d.setResponseBody("dispatch error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            scheduleRetry(d);
        }
        deliveryRepository.save(d);
    }

    private void scheduleRetry(WebhookDeliveryEntity d) {
        if (d.getAttempt() >= MAX_ATTEMPTS) {
            d.setStatus("FAILED");
            d.setNextRetryAt(null);
            return;
        }
        long secs = BACKOFF_SECONDS[Math.min(d.getAttempt() - 1, BACKOFF_SECONDS.length - 1)];
        d.setStatus("RETRY");
        d.setNextRetryAt(Instant.now().plus(secs, ChronoUnit.SECONDS));
    }

    /** Pull due retries every 30 seconds and re-attempt them. */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void retryDueDeliveries() {
        Instant now = Instant.now();
        List<WebhookDeliveryEntity> due = deliveryRepository
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc("RETRY", now);
        if (due.isEmpty())
            return;
        for (WebhookDeliveryEntity d : due) {
            // Mark PENDING first so we don't double-attempt if the scheduler overlaps.
            d.setStatus("PENDING");
            d.setNextRetryAt(null);
            deliveryRepository.save(d);
            // POR EL PROXY, igual que en queue(): con `this` la autoinvocación se salta @Async y los
            // reintentos corrían en serie dentro de la transacción del planificador, de modo que un
            // suscriptor lento retrasaba a todos los demás vencidos.
            self.attempt(d.getId());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null)
            return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Compute the X-NX036-Signature header value for a given body + secret (hex HMAC-SHA256). */
    public static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
