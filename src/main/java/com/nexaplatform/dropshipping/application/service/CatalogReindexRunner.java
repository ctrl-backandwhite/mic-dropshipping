package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ejecuta el reindexado completo del catálogo en SEGUNDO PLANO.
 *
 * <p>Reindexar todo el catálogo es una operación larga que crece con el nº de productos (con miles
 * tarda más de un minuto). Hacerlo de forma síncrona dentro de la petición HTTP la mataba: el proxy
 * del frontend (nginx, 60s por defecto) y, sobre todo, el borde de Cloudflare delante del
 * backend cortan la conexión y el admin veía "No se pudo reindexar" aunque el backend siguiera
 * trabajando. Por eso se dispara aquí en background y el endpoint responde al instante; el admin
 * consulta el estado (en curso / terminado) con un endpoint aparte.
 *
 * <p>El método {@link #runAsync()} lleva {@code @Async}, así que DEBE invocarse desde OTRO bean (nunca
 * por auto-invocación, que se saltaría el proxy) y sólo tras un {@link #tryAcquire()} con éxito, que
 * garantiza que no se solapen dos reindexados a la vez.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogReindexRunner {

    private final ProductIndexer productIndexer;
    private final ImageMirrorService imageMirrorService;

    /** true mientras hay un reindexado en curso (evita solapes). */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Nº de productos del último reindexado terminado (-1 si aún no se ha completado ninguno). */
    private final AtomicInteger lastIndexed = new AtomicInteger(-1);

    public boolean isRunning() {
        return running.get();
    }

    public int lastIndexed() {
        return lastIndexed.get();
    }

    /** Marca el inicio de forma atómica; devuelve false si YA había un reindexado en curso. */
    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    /**
     * Reindexado completo en background. Libera el flag {@code running} pase lo que pase (incluso si
     * revienta), para que un fallo no deje bloqueada la función de sincronizar.
     */
    @Async
    public void runAsync() {
        try {
            int n = productIndexer.reindexAll();
            lastIndexed.set(n);
            // Reindexar arrastra también el espejado de imágenes PENDING (cada lote reindexa sus
            // productos), para que "sincronizar" deje todo el catálogo visible en el escaparate.
            imageMirrorService.mirrorAllPendingAsync();
            log.info("::> [REINDEX] Reindexado completo en background: {} productos", n);
        } catch (RuntimeException e) {
            log.error("::> [REINDEX] Falló el reindexado en background: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
