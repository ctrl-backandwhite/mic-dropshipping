package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.application.service.Texts;
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
 * Reads the category catalog from the OpenSearch {@code categories} index (kept in sync by
 * {@link CategoryIndexer} on every create/update/delete). Used by the admin listing so categories are
 * served from the index. Queries OpenSearch over plain HTTP and parses the response with Jackson —
 * the typed opensearch-java client throws a "Jackson exception" on {@code search()} in this
 * client/server combo, while indexing works. Best-effort: any failure or an empty index returns
 * {@link Optional#empty()} so the caller falls back to the database.
 */
@Slf4j
@Service
public class CategorySearchService {

    /** Max categories pulled from the index in one shot (the catalog has at most a few thousand). */
    private static final int MAX = 10_000;

    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CategorySearchService(ObjectMapper objectMapper,
            @Value("${nexadrop.opensearch.uris:http://localhost:9400}") String uris,
            @Value("${nexadrop.opensearch.categories-index:categories}") String index) {
        this.objectMapper = objectMapper;
        String base = Texts.stripTrailingSlashes(uris.split(",")[0].trim());
        this.searchUrl = base + "/" + index + "/_search";
    }


    /** A flattened category row read from the OpenSearch index (everything the admin table needs). */
    public record IndexedCategory(UUID id, String slug, String nameZh, String nameEs, String nameEn, String namePt,
            String icon, int position, boolean active, UUID parentId) {
    }

    /**
     * Lists every category from the index, ordered by position. Optional free-text filter ({@code q})
     * matches slug and the translated names. Returns {@link Optional#empty()} when the index is empty or
     * OpenSearch is unavailable, signalling the caller to fall back to the database.
     */
    public Optional<List<IndexedCategory>> listFromIndex(String q) {
        try {
            String needle = (q == null || q.isBlank()) ? null : q.trim();
            String query = needle == null
                    ? "{\"match_all\":{}}"
                    : "{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(needle)
                            + ",\"fields\":[\"slug\",\"nameEs\",\"nameEn\",\"namePt\",\"nameZh\"]}}";
            String body = "{\"size\":" + MAX + ",\"query\":" + query
                    + ",\"sort\":[{\"position\":{\"order\":\"asc\"}}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Category index read returned {} — falling back to DB", res.statusCode());
                return Optional.empty();
            }
            JsonNode hits = objectMapper.readTree(res.body()).path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return Optional.empty(); // empty index → let the caller use the DB
            }
            List<IndexedCategory> rows = new ArrayList<>();
            for (JsonNode hit : hits) {
                IndexedCategory row = toRow(hit.path("_source"));
                if (row != null) {
                    rows.add(row);
                }
            }
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Category index read failed, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private IndexedCategory toRow(JsonNode s) {
        UUID id = uuid(text(s, "id"));
        if (id == null) {
            return null;
        }
        return new IndexedCategory(id, text(s, "slug"), text(s, "nameZh"), text(s, "nameEs"), text(s, "nameEn"),
                text(s, "namePt"), text(s, "icon"), s.path("position").asInt(0), s.path("active").asBoolean(false),
                uuid(text(s, "parentId")));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
