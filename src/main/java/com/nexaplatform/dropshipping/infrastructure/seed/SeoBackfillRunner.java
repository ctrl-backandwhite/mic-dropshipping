package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * DROP-679: al arrancar, genera el SEO (meta_title/meta_description) que falte en los productos ya
 * activos —publicados antes de existir la generación—. Es idempotente: solo rellena los huecos, así
 * que en arranques posteriores no hace nada. Desactivable con {@code nexadrop.seo-backfill.enabled=false}.
 */
@Component
@Order(50)
@ConditionalOnProperty(prefix = "nexadrop.seo-backfill", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SeoBackfillRunner implements ApplicationRunner {

    private final CatalogUseCase catalogUseCase;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = catalogUseCase.backfillMissingSeo();
            if (n == 0) {
                log.debug("::> [SEO] No products needed SEO backfill");
            }
        } catch (RuntimeException e) {
            log.warn("::> [SEO] SEO backfill skipped: {}", e.getMessage());
        }
    }
}
