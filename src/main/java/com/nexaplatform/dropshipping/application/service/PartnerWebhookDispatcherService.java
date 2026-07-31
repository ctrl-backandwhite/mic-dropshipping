package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DROP-663: delivers platform events to every active partner app webhook and records each attempt
 * in {@code partner_webhook_delivery} (the table the admin "Entregas de webhooks recientes" panel
 * reads). Previously nothing ever wrote to that table, so it always looked empty despite 50+ active
 * partner apps. Publishing only enqueues PENDING rows (cheap, never blocks the order transaction);
 * a scheduled drain posts them with HMAC-SHA256 signatures and retries with exponential backoff.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerWebhookDispatcherService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_SECONDS = {60, 300, 1800, 7200, 28800};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /** Enqueue one PENDING delivery per active partner app that has a webhook URL. */
    public void publish(String eventType, String eventId, Map<String, Object> data) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("createdAt", Instant.now().toString());
        envelope.put("data", data);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("Failed to serialize partner webhook payload for {}: {}", eventType, e.getMessage());
            return;
        }
        List<Map<String, Object>> apps = jdbc.queryForList(
                "SELECT id FROM partner_app WHERE active = true AND webhook_url IS NOT NULL AND webhook_url <> ''");
        for (Map<String, Object> app : apps) {
            jdbc.update("INSERT INTO partner_webhook_delivery"
                    + " (id, partner_app_id, event_type, payload, status, attempt_count, next_attempt_at, created_at)"
                    + " VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())",
                    UUID.randomUUID(), app.get("id"), eventType, payload);
        }
        if (!apps.isEmpty()) {
            log.info("::> [PARTNER-WH] queued {} deliveries for {}", apps.size(), eventType);
        }
    }

    /** Admin action: fire a synthetic {@code test.ping} to every active partner app. Returns the count. */
    public int dispatchTestToAll() {
        int before = countActiveTargets();
        publish("test.ping", "test-" + UUID.randomUUID(),
                Map.of("message", "NX036 partner webhook test", "at", Instant.now().toString()));
        return before;
    }

    private int countActiveTargets() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM partner_app WHERE active = true AND webhook_url IS NOT NULL AND webhook_url <> ''",
                Integer.class);
        return n != null ? n : 0;
    }

    /** Drains due PENDING / RETRY deliveries every 5s and posts them to the partner endpoints. */
    @Scheduled(fixedDelay = 5_000)
    public void drainDue() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT d.id, d.partner_app_id, d.event_type, d.payload, d.attempt_count,"
                        + " a.webhook_url, a.webhook_secret"
                        + " FROM partner_webhook_delivery d JOIN partner_app a ON a.id = d.partner_app_id"
                        + " WHERE d.status IN ('PENDING','RETRY') AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= now())"
                        + " ORDER BY d.created_at ASC LIMIT 50");
        for (Map<String, Object> row : due) {
            attempt(row);
        }
    }

    private void attempt(Map<String, Object> row) {
        UUID id = (UUID) row.get("id");
        String url = (String) row.get("webhook_url");
        String payload = (String) row.get("payload");
        String secret = (String) row.get("webhook_secret");
        String eventType = (String) row.get("event_type");
        int attempt = (row.get("attempt_count") == null ? 0 : ((Number) row.get("attempt_count")).intValue()) + 1;
        try {
            // La dirección la registra el partner: hay que comprobar que apunta a Internet ANTES de
            // llamarla. Sin esto, apuntando a 169.254.169.254 o a un servicio interno el servidor hace la
            // petición desde dentro de la red, y como el cuerpo de la respuesta se guarda en el registro
            // de entregas, el partner podría leer lo que conteste.
            PublicHttpUrl.assertPublic(URI.create(url));
            String signature = WebhookDispatcherService.sign(payload, secret != null ? secret : "");
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-NX036-Signature", signature)
                    .header("X-NX036-Event", eventType).header("X-NX036-Attempt", String.valueOf(attempt))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            finish(id, attempt, ok ? "SUCCESS" : retryStatus(attempt), res.statusCode(),
                    truncate(res.body(), 1000), ok ? null : nextRetry(attempt));
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            finish(id, attempt, retryStatus(attempt), null,
                    "dispatch error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), nextRetry(attempt));
        }
    }

    private void finish(UUID id, int attempt, String status, Integer code, String body, Timestamp nextRetry) {
        jdbc.update("UPDATE partner_webhook_delivery SET status = ?, attempt_count = ?, response_code = ?,"
                + " response_body = ?, last_attempt_at = now(), next_attempt_at = ? WHERE id = ?",
                status, attempt, code, body, nextRetry, id);
    }

    private static String retryStatus(int attempt) {
        return attempt >= MAX_ATTEMPTS ? "FAILED" : "RETRY";
    }

    private static Timestamp nextRetry(int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return null;
        }
        long secs = BACKOFF_SECONDS[Math.min(attempt - 1, BACKOFF_SECONDS.length - 1)];
        return Timestamp.from(Instant.now().plus(secs, ChronoUnit.SECONDS));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
