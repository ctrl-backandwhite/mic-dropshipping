package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Rellena el índice de búsqueda cuando acaba de crearse.
 *
 * <p>El esquema del índice está versionado ({@code products-v2}): al cambiar los analizadores no se puede
 * modificar el índice existente, hay que crear uno nuevo — y nace vacío. Sin esto, tras el despliegue la
 * búsqueda quedaría degradada al fallback SQL hasta que alguien pulsase "Sincronizar" en el admin, y nadie
 * se enteraría de que hay que hacerlo. Se reindexa una sola vez, en el arranque en que se creó el índice.
 *
 * <p>Va en segundo plano, reutilizando el mismo runner que el botón del admin, así que el arranque no se
 * bloquea; y respeta su cerrojo, de modo que no se solapa con un reindexado manual.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexBootstrap {

    private final ProductIndexer productIndexer;
    private final CatalogReindexRunner reindexRunner;

    /** Se desactiva en los tests: allí el índice se crea y se puebla a mano, sin barridos de fondo. */
    @Value("${nexadrop.search.auto-reindex:true}")
    private boolean autoReindex;

    @EventListener(ApplicationReadyEvent.class)
    public void reindexWhenIndexIsNew() {
        if (!autoReindex || !productIndexer.isFreshlyCreated()) {
            return;
        }
        if (!reindexRunner.tryAcquire()) {
            log.info("::> [SEARCH] índice nuevo, pero ya hay un reindexado en curso");
            return;
        }
        log.info("::> [SEARCH] índice '{}' recién creado — reindexando el catálogo en segundo plano",
                productIndexer.indexName());
        reindexRunner.runAsync();
    }
}
