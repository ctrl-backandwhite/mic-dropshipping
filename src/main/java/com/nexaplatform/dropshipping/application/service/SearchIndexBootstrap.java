package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
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

    /**
     * Por debajo de esta parte del catálogo se avisa de que el índice está incompleto.
     *
     * <p>No se exige coincidencia exacta: siempre hay productos que no llegan al índice por su cuenta —sin
     * imagen espejada, retirados a media pasada—, y una alarma que salta siempre deja de leerse.
     */
    private static final double PARTE_MINIMA_INDEXADA = 0.9;

    private final ProductIndexer productIndexer;
    private final ProductRepository productRepository;
    private final CatalogReindexRunner reindexRunner;

    /** Se desactiva en los tests: allí el índice se crea y se puebla a mano, sin barridos de fondo. */
    @Value("${nexadrop.search.auto-reindex:true}")
    private boolean autoReindex;

    @EventListener(ApplicationReadyEvent.class)
    public void reindexWhenIndexIsNew() {
        if (!autoReindex) {
            return;
        }
        if (!productIndexer.isFreshlyCreated()) {
            avisaSiElIndiceEstaIncompleto();
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

    /**
     * Avisa cuando el índice tiene MENOS productos de los que hay en el catálogo.
     *
     * <p>El reindexado automático de arriba corre UNA vez: en el arranque que crea el índice. Si esa pasada
     * se corta a medias —un despliegue, un reinicio, el buscador que se cae— nadie la reintenta nunca y el
     * índice se queda incompleto PARA SIEMPRE, sin un solo error en el registro. Pasó de verdad: en
     * preproducción el índice se quedó con 2.499 de 7.646 productos, y el síntoma que llegó no fue una
     * alarma sino «el buscador no funciona» — porque dos de cada tres productos no se podían encontrar,
     * aunque el listado sí los enseñaba (ese no pasa por el índice).
     *
     * <p><b>Avisa pero NO lo arregla solo, y es deliberado.</b> Reconstruir PURGA el índice antes de
     * repoblarlo, y en producción hay varias réplicas: todas verían la misma carencia al arrancar y todas
     * purgarían a la vez, dejando el buscador vacío mientras compiten. Repararlo sin riesgo necesita un
     * cerrojo compartido entre réplicas, y el que hay es por proceso. Con esto deja de ser invisible, que
     * es lo que de verdad costaba: la reconstrucción se lanza desde el panel, que ya la tiene.
     */
    private void avisaSiElIndiceEstaIncompleto() {
        long enElIndice = productIndexer.documentCount();
        if (enElIndice < 0) {
            return;
        }
        long enElCatalogo = productRepository.count();
        if (enElCatalogo == 0 || enElIndice >= enElCatalogo * PARTE_MINIMA_INDEXADA) {
            return;
        }
        log.warn("::> [SEARCH] el índice '{}' está INCOMPLETO: {} documentos para {} productos del catálogo. "
                + "La búsqueda no encontrará los que faltan aunque el listado sí los enseñe. "
                + "Reconstrúyelo desde el panel (Catálogo → Sincronizar).",
                productIndexer.indexName(), enElIndice, enElCatalogo);
    }
}
