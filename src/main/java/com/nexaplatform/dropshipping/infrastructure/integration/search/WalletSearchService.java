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
 * Reads the admin wallet listing from the OpenSearch {@code wallets} index (kept in sync by
 * {@link WalletIndexer}). Returns only the page of wallet IDs (newest-first), filtered by
 * status/currency/free-text; the use case then loads the balances fresh from the DB for those wallets
 * (money stays strictly consistent — the index only drives pagination/sort/filter). Best-effort:
 * any failure or empty index returns {@link Optional#empty()} so the caller falls back to the DB.
 */
@Slf4j
@Service
public class WalletSearchService {

    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WalletSearchService(ObjectMapper objectMapper,
            @Value("${nexadrop.opensearch.uris:http://localhost:9400}") String uris,
            @Value("${nexadrop.opensearch.wallets-index:wallets}") String index) {
        this.objectMapper = objectMapper;
        String base = uris.split(",")[0].trim().replaceAll("/+$", "");
        this.searchUrl = base + "/" + index + "/_search";
    }

    /** A page of wallet IDs (newest-first) plus the grand total for the filter. */
    public record IdPage(List<UUID> ids, long total) {
    }

    public Optional<IdPage> pageIds(String q, String status, String currency, int page, int size) {
        try {
            List<String> must = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                must.add("{\"term\":{\"status\":" + objectMapper.writeValueAsString(status.trim().toUpperCase()) + "}}");
            }
            if (currency != null && !currency.isBlank()) {
                must.add("{\"term\":{\"currency\":" + objectMapper.writeValueAsString(currency.trim().toUpperCase())
                        + "}}");
            }
            if (q != null && !q.isBlank()) {
                must.add("{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(q.trim())
                        + ",\"fields\":[\"userEmail\",\"userName\"]}}");
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
                log.warn("Wallet index page read returned {} — falling back to DB", res.statusCode());
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
            log.warn("Wallet index page read failed, falling back to DB: {}", e.getMessage());
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
