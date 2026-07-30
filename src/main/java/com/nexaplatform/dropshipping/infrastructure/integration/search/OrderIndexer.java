package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Indexes admin orders into OpenSearch (index {@code orders}) so the admin listing can paginate, sort
 * (newest-first) and filter (status / free-text) without scanning the whole table. Only the light
 * routing fields are stored (number, status, timestamp, names); the heavy cross-aggregate enrichment
 * stays in the DB and is applied only to the page. Mirrors {@link SupplierIndexer}: ensure on startup,
 * warm-up when empty, full reindex and per-change updates (from the order lifecycle). Best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIndexer {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String STANDARD = "standard";

    private final OpenSearchClient client;
    private final OrderRepository orderRepository;
    private final OrderSearchService orderSearchService;

    @Value("${nexadrop.opensearch.orders-index:orders}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices().create(CreateIndexRequest.of(b -> b.index(index)
                    .mappings(TypeMapping.of(tm -> tm.properties("status", Property.of(p -> p.keyword(k -> k)))
                            .properties("sortTs", Property.of(p -> p.date(d -> d)))
                            .properties("orderNumber", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("externalOrderId", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("shippingName", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (RuntimeException | IOException e) {
            // RuntimeException y no sólo OpenSearchException: esto corre en @PostConstruct, así que
            // cualquier fallo del cliente que no fuera exactamente esa excepción (una URL mal formada, un
            // certificado, un timeout envuelto) abortaba el ARRANQUE de la aplicación entera. Un buscador
            // caído degrada la búsqueda; nunca debe impedir vender.
            log.error("Failed to ensure OpenSearch order index: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        try {
            if (orderSearchService.pageIds(null, null, 0, 1).isEmpty()) {
                log.info("Order index '{}' empty/unavailable on startup → reindexing", index);
                reindexAll();
            }
        } catch (Exception e) {
            log.warn("Order index warm-up skipped: {}", e.getMessage());
        }
    }

    public void deleteFromIndex(UUID orderId) {
        try {
            client.delete(d -> d.index(index).id(orderId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for order {}: {}", orderId, e.getMessage());
        }
    }

    /** Re-indexes every order. Returns the number indexed. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        int[] n = { 0 };
        orderRepository.findAll().forEach(o -> {
            indexOrderModel(o);
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} orders into '{}'", n[0], index);
        return n[0];
    }

    /** Indexes (or refreshes) a single order by id. Best-effort; never breaks the write path. */
    @Transactional(readOnly = true)
    public void indexOrder(UUID orderId) {
        orderRepository.findById(orderId).ifPresent(this::indexOrderModel);
    }

    private void indexOrderModel(Order o) {
        Instant ts = o.getPlacedAt() != null ? o.getPlacedAt() : o.getCreatedAt();
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", o.getId().toString());
        doc.put("orderNumber", o.getOrderNumber());
        doc.put("externalOrderId", o.getExternalOrderId());
        doc.put("shippingName", o.getShippingFullName());
        doc.put("status", o.getStatus() != null ? o.getStatus().name() : null);
        doc.put("sortTs", ts != null ? ts.toString() : null);
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(o.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for order {}: {}", o.getId(), e.getMessage());
        }
    }
}
