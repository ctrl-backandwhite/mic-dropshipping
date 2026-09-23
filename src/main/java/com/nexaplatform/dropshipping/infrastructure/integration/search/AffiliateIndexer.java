package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateJpaRepositoryAdapter;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Indexes affiliates into OpenSearch (index {@code affiliates}) so the admin listing can paginate, sort
 * (newest-first) and filter (name/email/status) without scanning the table + N+1 lookups. Only routing
 * fields are stored (name/email/code/status/createdAt); the per-page rows (codes/commissions) are built
 * from the DB. Mirrors {@link SupplierIndexer}: ensure on startup, warm-up when empty, full reindex and
 * per-change updates (affiliate join / status change). Best-effort.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateIndexer {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String STANDARD = "standard";

    private final OpenSearchClient client;
    private final AffiliateJpaRepositoryAdapter affiliateRepo;
    private final AffiliateSearchService affiliateSearchService;

    @Value("${nexadrop.opensearch.affiliates-index:affiliates}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            if (client.indices().exists(b -> b.index(index)).value()) {
                return;
            }
            client.indices()
                    .create(CreateIndexRequest.of(b -> b.index(index)
                            .mappings(TypeMapping.of(tm -> tm.properties("status", Property.of(p -> p.keyword(k -> k)))
                                    .properties("createdAt", Property.of(p -> p.date(d -> d)))
                                    .properties("name", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                                    .properties("email", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                                    .properties("code", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (RuntimeException | IOException e) {
            // RuntimeException y no sólo OpenSearchException: esto corre en @PostConstruct, así que
            // cualquier fallo del cliente que no fuera exactamente esa excepción (una URL mal formada, un
            // certificado, un timeout envuelto) abortaba el ARRANQUE de la aplicación entera. Un buscador
            // caído degrada la búsqueda; nunca debe impedir vender.
            log.error("Failed to ensure OpenSearch affiliate index: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmUpOnStartup() {
        try {
            if (affiliateSearchService.pageIds(null, null, 0, 1).isEmpty()) {
                log.info("Affiliate index '{}' empty/unavailable on startup → reindexing", index);
                doReindexAll();
            }
        } catch (Exception e) {
            log.warn("Affiliate index warm-up skipped: {}", e.getMessage());
        }
    }

    public void deleteFromIndex(UUID affiliateId) {
        try {
            client.delete(d -> d.index(index).id(affiliateId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for affiliate {}: {}", affiliateId, e.getMessage());
        }
    }

    /** Re-indexes every affiliate. Returns the number indexed. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        return doReindexAll();
    }

    /**
     * Cuerpo del reindexado. Sin anotar: el arranque lo invoca desde dentro de la misma clase, y esa
     * autoinvocación se salta el proxy de Spring, con lo que el {@code @Transactional} del método público
     * no llegaría a aplicarse (java:S6809). La anotación se queda en el punto de entrada.
     */
    private int doReindexAll() {
        int[] n = {0};
        affiliateRepo.findAll().forEach(a -> {
            indexAffiliate(a);
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} affiliates into '{}'", n[0], index);
        return n[0];
    }

    /** Indexes (or refreshes) a single affiliate by id. Best-effort; never breaks the write path. */
    @Transactional(readOnly = true)
    public void indexAffiliate(UUID affiliateId) {
        affiliateRepo.findById(affiliateId).ifPresent(this::indexAffiliate);
    }

    /** Indexes an affiliate entity (resolves name/email from the owning user). */
    public void indexAffiliate(AffiliateEntity a) {
        if (a == null || a.getId() == null) {
            return;
        }
        UserEntity u = a.getUser();
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", a.getId().toString());
        doc.put("email", u != null ? u.getEmail() : null);
        doc.put("name", u != null ? fullName(u) : null);
        doc.put("code", a.getCode());
        doc.put("status", a.getStatus());
        doc.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(a.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for affiliate {}: {}", a.getId(), e.getMessage());
        }
    }

    private static String fullName(UserEntity u) {
        String s = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                + (u.getLastName1() == null ? "" : u.getLastName1()) + " "
                + (u.getLastName2() == null ? "" : u.getLastName2())).trim().replaceAll("\\s+", " ");
        return s.isBlank() ? u.getEmail() : s;
    }
}
