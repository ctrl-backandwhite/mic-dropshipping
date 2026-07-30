package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the admin affiliate listing from the OpenSearch {@code affiliates} index (kept in sync by
 * {@link AffiliateIndexer}). Returns only the page of affiliate IDs (newest-first), filtered by
 * free-text (name/email/status); the controller then builds the per-page rows (codes/commissions) from
 * the DB. Best-effort: any failure or empty index returns {@link Optional#empty()} → the caller falls
 * back to the DB listing.
 */
@Slf4j
@Service
public class AffiliateSearchService {

    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AffiliateSearchService(ObjectMapper objectMapper,
            @Value("${nexadrop.opensearch.uris:http://localhost:9400}") String uris,
            @Value("${nexadrop.opensearch.affiliates-index:affiliates}") String index) {
        this.objectMapper = objectMapper;
        String base = uris.split(",")[0].trim().replaceAll("/++$", "");
        this.searchUrl = base + "/" + index + "/_search";
    }

    /** A page of affiliate IDs (newest-first) plus the grand total for the filter. */
    public record IdPage(List<UUID> ids, long total) {
    }

    public Optional<IdPage> pageIds(String q, String status, int page, int size) {
        try {
            List<String> must = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                must.add("{\"term\":{\"status\":" + objectMapper.writeValueAsString(status.trim().toUpperCase()) + "}}");
            }
            if (q != null && !q.isBlank()) {
                must.add("{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(q.trim())
                        + ",\"fields\":[\"name\",\"email\",\"code\"]}}");
            }
            String query = must.isEmpty() ? "{\"match_all\":{}}" : "{\"bool\":{\"must\":[" + String.join(",", must) + "]}}";
            int from = Math.max(0, page) * size;
            String body = "{\"track_total_hits\":true,\"from\":" + from + ",\"size\":" + size + ",\"_source\":[\"id\"],"
                    + "\"query\":" + query + ",\"sort\":[{\"createdAt\":{\"order\":\"desc\"}}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Affiliate index page read returned {} — falling back to DB", res.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(res.body());
            long total = root.path("hits").path("total").path("value").asLong(0);
            if (total == 0) {
                return Optional.empty();
            }
            List<UUID> ids = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                String id = hit.path("_source").path("id").isMissingNode() ? hit.path("_id").asText()
                        : hit.path("_source").path("id").asText();
                UUID uuid = parse(id);
                if (uuid != null) {
                    ids.add(uuid);
                }
            }
            return ids.isEmpty() ? Optional.empty() : Optional.of(new IdPage(ids, total));
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Affiliate index page read failed, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static UUID parse(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
