package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import jakarta.annotation.PostConstruct;
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

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Indexes categories into OpenSearch (index {@code categories}) so the catalog can be searched and
 * filtered by name/slug/level/status. Mirrors {@link ProductIndexer}: ensures the index on startup,
 * supports a full reindex and incremental updates (called directly from the category use cases on
 * create/update/delete). Best-effort: OpenSearch being down never breaks the write path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryIndexer {

    private final OpenSearchClient client;
    private final CategoryRepository categoryRepository;
    private final CategorySearchService categorySearchService;

    @Value("${nexadrop.opensearch.categories-index:categories}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices().create(CreateIndexRequest.of(b -> b.index(index)
                    .mappings(TypeMapping.of(tm -> tm.properties("slug", Property.of(p -> p.keyword(k -> k)))
                            .properties("parentId", Property.of(p -> p.keyword(k -> k)))
                            .properties("parentSlug", Property.of(p -> p.keyword(k -> k)))
                            .properties("active", Property.of(p -> p.boolean_(bo -> bo)))
                            .properties("level", Property.of(p -> p.integer(i -> i)))
                            .properties("position", Property.of(p -> p.integer(i -> i)))
                            .properties("nameEs", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("nameEn", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("namePt", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("nameZh", Property.of(p -> p.text(t -> t.analyzer("standard"))))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (OpenSearchException | IOException e) {
            log.error("Failed to ensure OpenSearch category index: {}", e.getMessage());
        }
    }

    /**
     * Populates the index from the DB on startup when it is empty, so the OpenSearch-backed category
     * listing has data without needing a manual reindex. Combined with the per-change indexing
     * (create/update/delete), this keeps the index automatically in sync. Best-effort.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        try {
            // Reuse the HTTP-based read path (the typed opensearch-java search() throws here). Empty
            // Optional = empty index or OpenSearch down → (re)build it from the DB.
            if (categorySearchService.listFromIndex(null).isEmpty()) {
                log.info("Category index '{}' empty/unavailable on startup → reindexing", index);
                reindexAll();
            }
        } catch (Exception e) {
            log.warn("Category index warm-up skipped: {}", e.getMessage());
        }
    }

    /** Removes a single category document (best-effort). */
    public void deleteFromIndex(UUID categoryId) {
        try {
            client.delete(d -> d.index(index).id(categoryId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for category {}: {}", categoryId, e.getMessage());
        }
    }

    /** Re-indexes every category. Returns the number indexed. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        int[] n = { 0 };
        categoryRepository.findAll().forEach(c -> {
            indexEntity(c);
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} categories into '{}'", n[0], index);
        return n[0];
    }

    @Transactional(readOnly = true)
    public void indexCategory(UUID categoryId) {
        categoryRepository.findById(categoryId).ifPresent(this::indexEntity);
    }

    private void indexEntity(CategoryEntity c) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", c.getId().toString());
        doc.put("slug", c.getSlug());
        doc.put("active", c.isActive());
        doc.put("position", c.getPosition());
        doc.put("icon", c.getIcon());
        doc.put("nameZh", c.getNameZh());
        for (CategoryTranslationEntity t : c.getTranslations()) {
            doc.put("name" + capitalize(t.getLanguage()), t.getName());
        }
        // Level = depth in the tree (0 = root). Walk the lazy parent chain (guarded against cycles).
        int level = 0;
        CategoryEntity cur = c.getParent();
        Set<UUID> guard = new HashSet<>();
        guard.add(c.getId());
        while (cur != null && guard.add(cur.getId())) {
            level++;
            cur = cur.getParent();
        }
        doc.put("level", level);
        doc.put("parentId", c.getParent() != null ? c.getParent().getId().toString() : null);
        doc.put("parentSlug", c.getParent() != null ? c.getParent().getSlug() : null);
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(c.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for category {}: {}", c.getId(), e.getMessage());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
