package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
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
import java.util.Map;
import java.util.UUID;

/**
 * Indexes customer wallets into OpenSearch (index {@code wallets}) so the admin listing can paginate,
 * sort (newest-first) and filter (status/currency/free-text) without scanning the table. Only routing
 * fields are stored (email/name/currency/status/createdAt) — never the balance, which the use case
 * reads fresh from the DB for the page (money stays strictly consistent). Mirrors {@link SupplierIndexer}:
 * ensure on startup, warm-up when empty, full reindex and per-change updates. Best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletIndexer {

    private final OpenSearchClient client;
    private final WalletRepository walletRepository;
    private final WalletSearchService walletSearchService;

    @Value("${nexadrop.opensearch.wallets-index:wallets}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices().create(CreateIndexRequest.of(b -> b.index(index)
                    .mappings(TypeMapping.of(tm -> tm.properties("status", Property.of(p -> p.keyword(k -> k)))
                            .properties("currency", Property.of(p -> p.keyword(k -> k)))
                            .properties("createdAt", Property.of(p -> p.date(d -> d)))
                            .properties("userEmail", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("userName", Property.of(p -> p.text(t -> t.analyzer("standard"))))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (OpenSearchException | IOException e) {
            log.error("Failed to ensure OpenSearch wallet index: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        try {
            if (walletSearchService.pageIds(null, null, null, 0, 1).isEmpty()) {
                log.info("Wallet index '{}' empty/unavailable on startup → reindexing", index);
                reindexAll();
            }
        } catch (Exception e) {
            log.warn("Wallet index warm-up skipped: {}", e.getMessage());
        }
    }

    public void deleteFromIndex(UUID walletId) {
        try {
            client.delete(d -> d.index(index).id(walletId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for wallet {}: {}", walletId, e.getMessage());
        }
    }

    /** Re-indexes every wallet. Returns the number indexed. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        int[] n = { 0 };
        walletRepository.findAll().forEach(w -> {
            indexWallet(w);
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} wallets into '{}'", n[0], index);
        return n[0];
    }

    /** Indexes (or refreshes) a single wallet. Best-effort; never breaks the write path. */
    public void indexWallet(Wallet w) {
        if (w == null || w.getId() == null) {
            return;
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", w.getId().toString());
        doc.put("userEmail", w.getUserEmail());
        doc.put("userName", w.getUserName());
        doc.put("currency", w.getCurrencyDefault() != null ? w.getCurrencyDefault().toUpperCase() : null);
        doc.put("status", w.getStatus());
        doc.put("createdAt", w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(w.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for wallet {}: {}", w.getId(), e.getMessage());
        }
    }
}
