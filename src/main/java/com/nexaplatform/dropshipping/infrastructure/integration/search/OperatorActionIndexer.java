package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexa en OpenSearch el histórico de operaciones de los operadores y permite consultarlo paginado y
 * filtrado por rango de fechas (y opcionalmente por operador). Postgres es la fuente de verdad
 * ({@code operator_order_action}); aquí se replica para búsqueda/consulta desde OpenSearch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorActionIndexer {

    private final OpenSearchClient client;

    @Value("${nexadrop.opensearch.operator-actions-index:operator-actions}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices().create(CreateIndexRequest.of(b -> b.index(index).mappings(TypeMapping.of(tm -> tm
                    .properties("operatorSubject", Property.of(p -> p.keyword(k -> k)))
                    .properties("operatorEmail", Property.of(p -> p.keyword(k -> k)))
                    .properties("operatorName", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                    .properties("orderId", Property.of(p -> p.keyword(k -> k)))
                    .properties("orderNumber", Property.of(p -> p.keyword(k -> k)))
                    .properties("action", Property.of(p -> p.keyword(k -> k)))
                    .properties("commissionCnyCents", Property.of(p -> p.long_(l -> l)))
                    .properties("itemCount", Property.of(p -> p.integer(i -> i)))
                    .properties("processedAt", Property.of(p -> p.date(d -> d)))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (OpenSearchException | java.io.IOException e) {
            log.error("Failed to ensure OpenSearch index '{}': {}", index, e.getMessage());
        }
    }

    /** Indexa (best-effort) una acción del operador. No bloquea el flujo si OpenSearch no está. */
    public void index(OperatorOrderActionEntity a) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("operatorSubject", a.getOperatorSubject());
            doc.put("operatorEmail", a.getOperatorEmail());
            doc.put("operatorName", a.getOperatorName());
            doc.put("orderId", a.getOrderId() != null ? a.getOrderId().toString() : null);
            doc.put("orderNumber", a.getOrderNumber());
            doc.put("action", a.getAction());
            doc.put("commissionCnyCents", a.getCommissionCnyCents());
            doc.put("itemCount", a.getItemCount());
            doc.put("processedAt", a.getProcessedAt() != null ? a.getProcessedAt().toString() : null);
            client.index(IndexRequest.of(b -> b.index(index).id(a.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.warn("Index failed for operator action {}: {}", a.getId(), e.getMessage());
        }
    }

    /**
     * Consulta paginada por rango de fechas (procesado) y opcionalmente por operador. Devuelve
     * {items, total, page, size}. Lanza si OpenSearch no responde (el caller decide el fallback).
     */
    public Map<String, Object> search(String operatorSubject, Instant from, Instant to, int page, int size)
            throws java.io.IOException {
        String fromS = from.toString();
        String toS = to.toString();
        Query range = Query.of(q -> q.range(r -> r.field("processedAt").gte(jsonData(fromS)).lte(jsonData(toS))));
        Query query = operatorSubject == null || operatorSubject.isBlank()
                ? range
                : Query.of(q -> q.bool(b -> b.must(range)
                        .must(Query.of(qq -> qq.term(t -> t.field("operatorSubject").value(v -> v.stringValue(
                                operatorSubject)))))));
        SearchResponse<Map> resp = client.search(SearchRequest.of(s -> s.index(index).from(page * size).size(size)
                .query(query).sort(srt -> srt.field(f -> f.field("processedAt").order(SortOrder.Desc)))), Map.class);
        List<Map<String, Object>> items = new ArrayList<>();
        resp.hits().hits().forEach(h -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> src = h.source();
            if (src != null) {
                items.add(src);
            }
        });
        Map<String, Object> out = new HashMap<>();
        out.put("items", items);
        out.put("total", resp.hits().total() != null ? resp.hits().total().value() : (long) items.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    private static org.opensearch.client.json.JsonData jsonData(String iso) {
        return org.opensearch.client.json.JsonData.of(iso);
    }
}
