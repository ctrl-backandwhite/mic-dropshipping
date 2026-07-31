package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.application.service.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the supplier catalog from the OpenSearch {@code suppliers} index (kept in sync by
 * {@link SupplierIndexer} on every create/update/delete). Used by the storefront listing so suppliers
 * are served from the index. Mirrors {@link CategorySearchService}: queries OpenSearch over plain HTTP
 * and parses the response with Jackson (the typed opensearch-java {@code search()} throws a "Jackson
 * exception" in this client/server combo, while indexing works). Best-effort: any failure or an empty
 * index returns {@link Optional#empty()} so the caller falls back to the database.
 */
@Slf4j
@Service
public class SupplierSearchService {
    /** Tope de resultados por página: por encima, la consulta la paga el índice sin que nadie la lea. */
    private static final int MAX_PAGE_SIZE = 100;
    /**
     * Tope de desplazamiento. OpenSearch rechaza from+size por encima de index.max_result_window
     * (10.000 por defecto), así que pedir la página un millón devolvía un error del índice y una caída
     * silenciosa a base de datos en vez de una página vacía.
     */
    private static final int MAX_FROM = 10_000;


    /** Max suppliers pulled from the index in one shot (the catalog has at most a few thousand). */
    private static final int MAX = 10_000;

    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SupplierSearchService(ObjectMapper objectMapper,
            @Value("${nexadrop.opensearch.uris:http://localhost:9400}") String uris,
            @Value("${nexadrop.opensearch.suppliers-index:suppliers}") String index) {
        this.objectMapper = objectMapper;
        // Sólo el primer nodo de la lista: este cliente no hace balanceo, apunta a uno.
        this.searchUrl = Texts.stripTrailingSlashes(uris.split(",")[0].trim()) + "/" + index + "/_search";
    }


    /** A flattened supplier row read from the OpenSearch index (everything the listing needs). */
    public record IndexedSupplier(UUID id, String externalId, String name, String nameZh, String country, String city,
            BigDecimal rating, Integer yearsActive, boolean verified, boolean trustPass, long productCount) {
    }

    /** A page of suppliers read from the index (items for this page + the grand total). */
    public record IndexedPage(List<IndexedSupplier> items, long total) {
    }

    /**
     * Paginated supplier listing from the index, ordered by {@code createdAt} desc (most recent first).
     * Optional free-text filter {@code q}. Returns {@link Optional#empty()} when the index is empty or
     * OpenSearch is unavailable so the caller can fall back to a paginated DB query.
     */
    public Optional<IndexedPage> pageFromIndex(String q, String country, Boolean verified, int page, int size) {
        try {
            String needle = (q == null || q.isBlank()) ? null : q.trim();
            List<String> must = new ArrayList<>();
            if (needle != null) {
                must.add("{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(needle)
                        + ",\"fields\":[\"name\",\"nameZh\",\"country\",\"city\",\"externalId\"]}}");
            }
            if (country != null && !country.isBlank()) {
                must.add("{\"term\":{\"country\":" + objectMapper.writeValueAsString(country.trim()) + "}}");
            }
            if (verified != null) {
                must.add("{\"term\":{\"verified\":" + verified + "}}");
            }
            String query = must.isEmpty() ? "{\"match_all\":{}}"
                    : "{\"bool\":{\"must\":[" + String.join(",", must) + "]}}";
            // page y size llegan del cliente: se acotan los DOS. Con size=0 salía una página vacía con
            // total>0 (el listado parecía roto) y un page enorme desbordaba el int hasta un from negativo,
            // que OpenSearch rechaza con 400 y aquí acababa en caída a base de datos.
            int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
            int from = (int) Math.min((long) Math.max(0, page) * pageSize, MAX_FROM);
            String body = "{\"track_total_hits\":true,\"from\":" + from + ",\"size\":" + pageSize + ",\"query\":" + query
                    + ",\"sort\":[{\"createdAt\":{\"order\":\"desc\"}}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Supplier index page read returned {} — falling back to DB", res.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(res.body());
            JsonNode hitsNode = root.path("hits").path("hits");
            long total = root.path("hits").path("total").path("value").asLong(0);
            if (total == 0) {
                return Optional.empty(); // empty index → let the caller use the DB
            }
            List<IndexedSupplier> rows = new ArrayList<>();
            for (JsonNode hit : hitsNode) {
                IndexedSupplier row = toRow(hit.path("_source"));
                if (row != null) {
                    rows.add(row);
                }
            }
            return Optional.of(new IndexedPage(rows, total));
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Supplier index page read failed, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lists every supplier from the index, ordered by name (case-insensitive). Optional free-text filter
     * ({@code q}) matches name/nameZh/country/city. Returns {@link Optional#empty()} when the index is
     * empty or OpenSearch is unavailable, signalling the caller to fall back to the database.
     */
    public Optional<List<IndexedSupplier>> listFromIndex(String q) {
        try {
            String needle = (q == null || q.isBlank()) ? null : q.trim();
            String query = needle == null
                    ? "{\"match_all\":{}}"
                    : "{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(needle)
                            + ",\"fields\":[\"name\",\"nameZh\",\"country\",\"city\"]}}";
            String body = "{\"size\":" + MAX + ",\"query\":" + query + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Supplier index read returned {} — falling back to DB", res.statusCode());
                return Optional.empty();
            }
            JsonNode hits = objectMapper.readTree(res.body()).path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return Optional.empty(); // empty index → let the caller use the DB
            }
            List<IndexedSupplier> rows = new ArrayList<>();
            for (JsonNode hit : hits) {
                IndexedSupplier row = toRow(hit.path("_source"));
                if (row != null) {
                    rows.add(row);
                }
            }
            // Sort here (the name field is text/non-sortable in OpenSearch); mirrors the DB path.
            rows.sort(Comparator.comparing(r -> r.name() == null ? "" : r.name(), String.CASE_INSENSITIVE_ORDER));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Supplier index read failed, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private IndexedSupplier toRow(JsonNode s) {
        UUID id = uuid(text(s, "id"));
        if (id == null) {
            return null;
        }
        JsonNode ratingNode = s.path("rating");
        BigDecimal rating = ratingNode.isNull() || ratingNode.isMissingNode() ? null
                : BigDecimal.valueOf(ratingNode.asDouble());
        JsonNode yearsNode = s.path("yearsActive");
        Integer yearsActive = yearsNode.isNull() || yearsNode.isMissingNode() ? null : yearsNode.asInt();
        return new IndexedSupplier(id, text(s, "externalId"), text(s, "name"), text(s, "nameZh"), text(s, "country"),
                text(s, "city"), rating, yearsActive, s.path("verified").asBoolean(false),
                s.path("trustPass").asBoolean(false), s.path("productCount").asLong(0));
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
