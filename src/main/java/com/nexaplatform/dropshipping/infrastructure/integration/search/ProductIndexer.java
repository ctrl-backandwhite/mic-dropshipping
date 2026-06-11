package com.nexaplatform.dropshipping.infrastructure.integration.search;

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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexer {

    private final OpenSearchClient client;
    private final ProductRepository productRepository;

    @Value("${nexadrop.opensearch.products-index}")
    private String index;

    @PostConstruct
    public void ensureIndex() {
        try {
            boolean exists = client.indices().exists(b -> b.index(index)).value();
            if (exists) return;
            client.indices().create(CreateIndexRequest.of(b -> b
                    .index(index)
                    .mappings(TypeMapping.of(tm -> tm
                            .properties("slug", Property.of(p -> p.keyword(k -> k)))
                            .properties("source", Property.of(p -> p.keyword(k -> k)))
                            .properties("categoryId", Property.of(p -> p.keyword(k -> k)))
                            .properties("status", Property.of(p -> p.keyword(k -> k)))
                            .properties("titleZh", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("titleEs", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("titleEn", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("titlePt", Property.of(p -> p.text(t -> t.analyzer("standard"))))
                            .properties("description", Property.of(p -> p.text(t -> t)))
                            .properties("basePrice", Property.of(p -> p.scaledFloat(sf -> sf.scalingFactor(10000.0))))
                            .properties("trendScore", Property.of(p -> p.float_(f -> f)))
                            .properties("monthlySales", Property.of(p -> p.integer(i -> i)))
                            .properties("rating", Property.of(p -> p.float_(f -> f)))
                            .properties("supplierId", Property.of(p -> p.keyword(k -> k)))
                    ))));
            log.info("Created OpenSearch index '{}'", index);
        } catch (OpenSearchException | java.io.IOException e) {
            log.error("Failed to ensure OpenSearch index: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = NexaTopics.PRODUCT_INGESTED, groupId = "nexadrop-search-indexer")
    public void onProductIngested(ProductIngestedEvent event) {
        indexProduct(event.productId());
    }

    @Transactional(readOnly = true)
    public void indexProduct(UUID productId) {
        ProductEntity p = productRepository.findWithDetailsById(productId).orElse(null);
        if (p == null) return;
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
        if (!p.getImages().isEmpty()) {
            var img = p.getImages().get(0);
            doc.put("mainImage", img.getCdnUrl() != null ? img.getCdnUrl() : img.getSourceUrl());
        }
        try {
            client.index(IndexRequest.of(b -> b.index(index).id(p.getId().toString()).document(doc)));
        } catch (Exception e) {
            log.error("Index failed for product {}: {}", productId, e.getMessage());
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
