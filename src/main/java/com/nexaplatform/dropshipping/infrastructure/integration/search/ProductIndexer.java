package com.nexaplatform.dropshipping.infrastructure.integration.search;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexer {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String STANDARD = "standard";

    private final OpenSearchClient client;
    private final ProductRepository productRepository;

    @Value("${nexadrop.opensearch.products-index}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(b -> b.index(index)).value();
            if (exists)
                return;
            client.indices().create(CreateIndexRequest.of(b -> b.index(index)
                    .mappings(TypeMapping.of(tm -> tm.properties("slug", Property.of(p -> p.keyword(k -> k)))
                            .properties("source", Property.of(p -> p.keyword(k -> k)))
                            .properties("categoryId", Property.of(p -> p.keyword(k -> k)))
                            .properties("status", Property.of(p -> p.keyword(k -> k)))
                            .properties("titleZh", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("titleEs", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("titleEn", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("titlePt", Property.of(p -> p.text(t -> t.analyzer(STANDARD))))
                            .properties("description", Property.of(p -> p.text(t -> t)))
                            .properties("basePrice", Property.of(p -> p.scaledFloat(sf -> sf.scalingFactor(10000.0))))
                            .properties("trendScore", Property.of(p -> p.float_(f -> f)))
                            .properties("monthlySales", Property.of(p -> p.integer(i -> i)))
                            .properties("rating", Property.of(p -> p.float_(f -> f)))
                            .properties("supplierId", Property.of(p -> p.keyword(k -> k)))))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (RuntimeException | IOException e) {
            // RuntimeException y no sólo OpenSearchException: esto corre en @PostConstruct, así que
            // cualquier fallo del cliente que no fuera exactamente esa excepción (una URL mal formada, un
            // certificado, un timeout envuelto) abortaba el ARRANQUE de la aplicación entera. Un buscador
            // caído degrada la búsqueda; nunca debe impedir vender.
            log.error("Failed to ensure OpenSearch index: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = NexaTopics.PRODUCT_INGESTED, groupId = "nexadrop-search-indexer")
    // La transacción tiene que abrirse AQUÍ: el cuerpo del indexado se llama en la misma clase y una
    // llamada interna no pasa por el proxy de Spring, así que el indexado corría sin sesión JPA.
    @Transactional(readOnly = true)
    public void onProductIngested(ProductIngestedEvent event) {
        indexProductDoc(event.productId());
    }

    /** Removes a single product document from the search index (best-effort). */
    public void deleteFromIndex(UUID productId) {
        try {
            client.delete(d -> d.index(index).id(productId.toString()));
        } catch (Exception e) {
            log.warn("Index delete failed for product {}: {}", productId, e.getMessage());
        }
    }

    /**
     * Re-indexes every product into OpenSearch. {@code @Transactional} keeps one session open
     * for the whole sweep so the per-product indexing body can read the lazy
     * {@code translations}/{@code images} collections. Returns the number indexed.
     */
    @Transactional(readOnly = true)
    public int reindexAll() {
        // PURGA primero: borra los documentos obsoletos (productos ya eliminados de la BD) para que el
        // índice quede EXACTAMENTE igual que la BD. Sin esto, un producto borrado seguía apareciendo en
        // la búsqueda (documento huérfano con un UUID que ya no existe) porque el reindex solo hacía upsert.
        purgeIndex();
        int[] n = { 0 };
        productRepository.findAll().forEach(p -> {
            indexProductDoc(p.getId());
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} products into '{}' (índice purgado antes de reconstruir)", n[0], index);
        return n[0];
    }

    /** Elimina TODOS los documentos del índice de productos (match_all), conservando el índice y su mapping. */
    private void purgeIndex() {
        try {
            client.deleteByQuery(d -> d.index(index).query(q -> q.matchAll(m -> m)).refresh(true));
            log.info("::> [REINDEX] purged stale documents from '{}'", index);
        } catch (OpenSearchException | IOException e) {
            log.warn("Index purge failed for '{}': {} (se continúa con el upsert)", index, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void indexProduct(UUID productId) {
        indexProductDoc(productId);
    }

    /**
     * Cuerpo del indexado, SIN anotar. Es al que llaman {@link #onProductIngested} y {@link #reindexAll},
     * que ya abren la sesión JPA: anotarlo de nuevo aquí no serviría de nada porque una llamada dentro de
     * la misma instancia no atraviesa el proxy de Spring.
     */
    private void indexProductDoc(UUID productId) {
        ProductEntity p = productRepository.findWithDetailsById(productId).orElse(null);
        if (p == null)
            return;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", p.getId().toString());
        doc.put("slug", p.getSlug());
        doc.put("source", p.getSource());
        doc.put("externalId", p.getExternalId());
        doc.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        doc.put("titleZh", p.getTitleZh());
        for (ProductTranslationEntity t : p.getTranslations()) {
            doc.put("title" + capitalize(t.getLanguage()), t.getTitle());
            doc.put("description" + capitalize(t.getLanguage()), t.getShortDescription());
        }
        doc.put("basePrice", p.getBasePrice());
        doc.put("trendScore", p.getTrendScore());
        doc.put("monthlySales", p.getMonthlySales());
        doc.put("rating", p.getRating());
        doc.put("supplierId", p.getSupplier() != null ? p.getSupplier().getId().toString() : null);
        doc.put("categoryId", p.getCategory() != null ? p.getCategory().getId().toString() : null);
        // hasImage: true solo si hay al menos una imagen ya espejada a nuestro storage (cdn_url no nulo).
        // La búsqueda filtra por este flag para no devolver productos cuya imagen no renderiza (igual
        // criterio que el filtro SQL del escaparate).
        boolean hasMirroredImage = p.getImages().stream().anyMatch(i -> i.getCdnUrl() != null);
        doc.put("hasImage", hasMirroredImage);
        if (!p.getImages().isEmpty()) {
            ProductImageEntity img = p.getImages().get(0);
            doc.put("mainImage", img.getCdnUrl() != null ? img.getCdnUrl() : img.getSourceUrl());
        }
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(p.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for product {}: {}", productId, e.getMessage());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
