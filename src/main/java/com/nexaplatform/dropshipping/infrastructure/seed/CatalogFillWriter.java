package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persists a single generated demo product in its own transaction: ingests it via
 * the real catalog pipeline (so the Kafka {@code product.ingested} event is emitted
 * and the search index is kept in sync), attaches the four UI translations and
 * publishes it (DRAFT → ACTIVE + trend score). Kept as a separate bean so each
 * product commits independently, avoiding one giant seed transaction for 700 rows.
 */
@Component
@RequiredArgsConstructor
public class CatalogFillWriter {

    private final CatalogUseCase catalogService;
    private final ProductRepository productRepository;

    @Transactional
    public UUID write(IngestProductRequest req, String esTitle, String enTitle, String zhTitle, String esDesc,
            String enDesc) {
        ProductEntity saved = catalogService.upsertProduct(req);
        ProductEntity managed = productRepository.findById(saved.getId()).orElse(null);
        if (managed == null)
            return saved.getId();

        managed.getTranslations().clear();
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("es").title(esTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("en").title(enTitle)
                .shortDescription(enDesc).description(enDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("zh").title(zhTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("pt").title(esTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());

        // Publish straight away (mirrors DemoCatalogSeedRunner.publishAll trend formula).
        managed.setStatus(ProductStatus.ACTIVE);
        double salesNorm = Math.min(1.0, managed.getMonthlySales() / 1000.0);
        double rating = managed.getRating() != null ? managed.getRating().doubleValue() / 5.0 : 0;
        double repurchase = managed.getRepurchaseRate() != null ? managed.getRepurchaseRate().doubleValue() / 100.0 : 0;
        double reviews = Math.min(1.0, managed.getReviewCount() / 500.0);
        double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
        managed.setTrendScore(BigDecimal.valueOf(Math.round(score * 10000) / 10000.0));

        productRepository.save(managed);
        return saved.getId();
    }
}
