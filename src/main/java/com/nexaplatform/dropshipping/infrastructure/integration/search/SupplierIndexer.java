package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indexes suppliers into OpenSearch (index {@code suppliers}) so the catalog can list/search them
 * without hitting Postgres. Mirrors {@link CategoryIndexer}: ensures the index on startup, warms it up
 * from the DB when empty, supports a full reindex and incremental updates (called directly from the
 * supplier use case on create/update/delete). The per-supplier {@code productCount} is embedded at
 * index time (refreshed on reindex / warm-up). Best-effort: OpenSearch being down never breaks writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierIndexer {

    private final OpenSearchClient client;
    private final SupplierRepository supplierRepository;
    private final SupplierSearchService supplierSearchService;

    @PersistenceContext
    private EntityManager em;

    @Value("${nexadrop.opensearch.suppliers-index:suppliers}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices().create(CreateIndexRequest.of(b -> b.index(index)
                    .mappings(TypeMapping.of(tm -> tm.properties("externalId", Property.of(p -> p.keyword(k -> k)))
                            .properties("source", Property.of(p -> p.keyword(k -> k)))
                            .properties("country", Property.of(p -> p.keyword(k -> k)))
                            .properties("city", Property.of(p -> p.keyword(k -> k)))
                            .properties("verified", Property.of(p -> p.boolean_(bo -> bo)))
                            .properties("trustPass", Property.of(p -> p.boolean_(bo -> bo)))
                            .properties("rating", Property.of(p -> p.double_(d -> d)))
                            .properties("yearsActive", Property.of(p -> p.integer(i -> i)))
                            .properties("productCount", Property.of(p -> p.integer(i -> i)))
                            .properties("createdAt", Property.of(p -> p.date(dt -> dt)))
                            .properties("name", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("nameZh", Property.of(p -> p.text(t -> t.analyzer("standard"))))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (OpenSearchException | java.io.IOException e) {
            log.error("Failed to ensure OpenSearch supplier index: {}", e.getMessage());
        }
    }

    /**
     * Populates the index from the DB on startup when it is empty, so the OpenSearch-backed supplier
     * listing has data without needing a manual reindex. Combined with the per-change indexing, this
     * keeps the index automatically in sync. Best-effort.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        try {
            if (supplierSearchService.listFromIndex(null).isEmpty()) {
                log.info("Supplier index '{}' empty/unavailable on startup → reindexing", index);
                reindexAll();
            }
        } catch (Exception e) {
            log.warn("Supplier index warm-up skipped: {}", e.getMessage());
        }
    }

    /** Removes a single supplier document (best-effort). */
    public void deleteFromIndex(UUID supplierId) {
        try {
            client.delete(d -> d.index(index).id(supplierId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for supplier {}: {}", supplierId, e.getMessage());
        }
    }

    /** Re-indexes every supplier (with a fresh product-count snapshot). Returns the number indexed. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        Map<UUID, Long> counts = productCountBySupplier();
        int[] n = { 0 };
        supplierRepository.findAll().forEach(s -> {
            indexEntity(s, counts.getOrDefault(s.getId(), 0L));
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} suppliers into '{}'", n[0], index);
        return n[0];
    }

    /** Indexes (or refreshes) a single supplier by id, recomputing its product count. */
    @Transactional(readOnly = true)
    public void indexSupplier(UUID supplierId) {
        supplierRepository.findById(supplierId).ifPresent(s -> indexEntity(s, productCountOf(supplierId)));
    }

    private void indexEntity(SupplierEntity s, long productCount) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", s.getId().toString());
        doc.put("externalId", s.getExternalId());
        doc.put("source", s.getSource());
        doc.put("name", s.getName());
        doc.put("nameZh", s.getNameZh());
        doc.put("country", s.getCountry());
        doc.put("city", s.getCity());
        doc.put("rating", s.getRating());
        doc.put("yearsActive", s.getYearsActive());
        doc.put("verified", s.isVerified());
        doc.put("trustPass", s.isTrustPass());
        doc.put("productCount", productCount);
        doc.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(s.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for supplier {}: {}", s.getId(), e.getMessage());
        }
    }

    private Map<UUID, Long> productCountBySupplier() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createQuery(
                "SELECT p.supplier.id, COUNT(p) FROM ProductEntity p WHERE p.supplier IS NOT NULL GROUP BY p.supplier.id")
                .getResultList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private long productCountOf(UUID supplierId) {
        Long c = em.createQuery("SELECT COUNT(p) FROM ProductEntity p WHERE p.supplier.id = :id", Long.class)
                .setParameter("id", supplierId).getSingleResult();
        return c != null ? c : 0L;
    }
}
