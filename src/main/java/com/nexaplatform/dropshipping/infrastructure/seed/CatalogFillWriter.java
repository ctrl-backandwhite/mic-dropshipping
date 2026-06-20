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

    /** Legacy 6-arg shape used by the demo filler: PT falls back to ES, no extra enrichment. */
    @Transactional
    public UUID write(IngestProductRequest req, String esTitle, String enTitle, String zhTitle, String esDesc,
            String enDesc) {
        return write(req, esTitle, enTitle, esTitle, zhTitle, esDesc, enDesc, esDesc, esDesc, p -> {
        });
    }

    /**
     * Full variant: per-language titles AND descriptions, plus an {@code enrich} hook that runs on the
     * managed entity inside this transaction (used by the bulk importer to set logistics/customs fields
     * without hitting a LazyInitializationException).
     */
    @Transactional
    public UUID write(IngestProductRequest req, String esTitle, String enTitle, String ptTitle, String zhTitle,
            String descEs, String descEn, String descPt, String descZh,
            java.util.function.Consumer<ProductEntity> enrich) {
        ProductEntity saved = catalogService.upsertProduct(req);
        ProductEntity managed = productRepository.findById(saved.getId()).orElse(null);
        if (managed == null)
            return saved.getId();

        // Upsert in-place por idioma. NO usamos clear()+add: con orphanRemoval, Hibernate ejecuta los
        // INSERT antes que los DELETE en el flush, y al reimportar un producto existente chocaría con la
        // unique (product_id, language) [ux_prodtr_prod_lang]. Actualizando la fila existente (o creándola
        // si falta) la reimportación es idempotente y nunca viola la constraint.
        upsertTranslation(managed, "es", esTitle, descEs);
        upsertTranslation(managed, "en", blankTo(enTitle, esTitle), blankTo(descEn, descEs));
        upsertTranslation(managed, "zh", zhTitle, blankTo(descZh, descEs));
        upsertTranslation(managed, "pt", blankTo(ptTitle, esTitle), blankTo(descPt, descEs));

        if (enrich != null) {
            enrich.accept(managed);
        }

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

    /** Crea o actualiza la traducción del idioma sin borrar/reinsertar (evita el choque de unique). */
    private static void upsertTranslation(ProductEntity p, String lang, String title, String desc) {
        String shortDesc = desc != null && desc.length() > 2000 ? desc.substring(0, 2000) : desc;
        ProductTranslationEntity existing = p.getTranslations().stream()
                .filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst().orElse(null);
        if (existing != null) {
            existing.setTitle(title);
            existing.setShortDescription(shortDesc);
            existing.setDescription(desc);
        } else {
            p.getTranslations().add(ProductTranslationEntity.builder().product(p).language(lang).title(title)
                    .shortDescription(shortDesc).description(desc).provider("seed").build());
        }
    }

    private static String blankTo(String v, String fallback) {
        return v != null && !v.isBlank() ? v : fallback;
    }
}
