package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.application.service.Texts;
import com.fasterxml.jackson.core.JsonProcessingException;
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
 * Reads the admin order listing from the OpenSearch {@code orders} index (kept in sync by
 * {@link OrderIndexer}). Returns only the page of order IDs in the requested order (most-recent-first),
 * filtered by status/free-text; the use case then loads + enriches just those orders from the DB. This
 * keeps the heavy cross-aggregate enrichment (customer/shop/supplier) off the full table while still
 * paginating and sorting from the index. Best-effort: any failure or an empty index returns
 * {@link Optional#empty()} so the caller falls back to a DB-side paginated listing.
 */
@Slf4j
@Service
public class OrderSearchService {

    private final ObjectMapper objectMapper;
    private final String searchUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OrderSearchService(ObjectMapper objectMapper,
            @Value("${nexadrop.opensearch.uris:http://localhost:9400}") String uris,
            @Value("${nexadrop.opensearch.orders-index:orders}") String index) {
        this.objectMapper = objectMapper;
        String base = Texts.stripTrailingSlashes(uris.split(",")[0].trim());
        this.searchUrl = base + "/" + index + "/_search";
    }


    /** A page of order IDs (in newest-first order) plus the grand total for the filter. */
    public record IdPage(List<UUID> ids, long total) {
    }

    public Optional<IdPage> pageIds(String status, String q, int page, int size) {
        try {
            int from = Math.max(0, page) * size;
            String body = "{\"track_total_hits\":true,\"from\":" + from + ",\"size\":" + size + ",\"_source\":[\"id\"],"
                    + "\"query\":" + buildQuery(status, q) + ",\"sort\":[{\"sortTs\":{\"order\":\"desc\"}}]}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(searchUrl)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Order index page read returned {} — falling back to DB", res.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(res.body());
            long total = root.path("hits").path("total").path("value").asLong(0);
            if (total == 0) {
                return Optional.empty();
            }
            List<UUID> ids = extractIds(root);
            return ids.isEmpty() ? Optional.empty() : Optional.of(new IdPage(ids, total));
        } catch (Exception e) {
            // Un fallo de red y una interrupción del hilo llegan por el mismo catch. Tragarse la
            // interrupción deja al pool sin enterarse de que le han pedido parar.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Order index page read failed, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Cuerpo del {@code query}: filtro por estado y/o texto libre. Los valores se serializan con Jackson
     * (nunca concatenados en crudo) para que una comilla en la búsqueda no rompa el JSON de la consulta.
     */
    private String buildQuery(String status, String q) throws JsonProcessingException {
        List<String> must = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            must.add("{\"term\":{\"status\":" + objectMapper.writeValueAsString(status.trim().toUpperCase()) + "}}");
        }
        if (q != null && !q.isBlank()) {
            must.add("{\"multi_match\":{\"query\":" + objectMapper.writeValueAsString(q.trim())
                    + ",\"fields\":[\"orderNumber\",\"externalOrderId\",\"shippingName\"]}}");
        }
        return must.isEmpty() ? "{\"match_all\":{}}" : "{\"bool\":{\"must\":[" + String.join(",", must) + "]}}";
    }

    /** IDs de los hits, en el orden en el que llegan del índice. Un id ilegible se descarta, no rompe la página. */
    private static List<UUID> extractIds(JsonNode root) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            String id = hit.path("_source").path("id").isMissingNode() ? hit.path("_id").asText()
                    : hit.path("_source").path("id").asText();
            UUID uuid = parse(id);
            if (uuid != null) {
                ids.add(uuid);
            }
        }
        return ids;
    }

    private static UUID parse(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
