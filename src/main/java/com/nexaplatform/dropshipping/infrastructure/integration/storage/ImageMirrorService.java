package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import com.nexaplatform.dropshipping.application.service.Texts;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ImagenOrigenEspejadaEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ImagenOrigenEspejadaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Espeja a object storage (MinIO/S3) las imágenes de producto: descarga la {@code source_url} de origen
 * (1688/alicdn) <b>desde el servidor, sin Referer</b> (así no la bloquea el hotlink), la sube al bucket y
 * fija la {@code cdn_url} pública. Como la capa de vista ya prefiere {@code cdn_url} sobre {@code source_url},
 * la web pasa a servir desde nuestro storage automáticamente.
 *
 * <p>Drena las imágenes en estado {@code PENDING}: cubre tanto la <b>importación</b> (cada imagen nueva nace
 * PENDING) como el <b>backfill</b> de las existentes. Job programado + en paralelo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageMirrorService {

    private final ProductImageRepository imageRepository;
    private final ProductVariantRepository variantRepository;
    private final VariantValueRepository variantValueRepository;
    private final ObjectStorageService storage;
    private final CompresorDeImagen compresor;
    private final ProductIndexer productIndexer;
    private final ImagenOrigenEspejadaRepository origenesEspejados;
    private final LimitadorDeDescargasPorOrigen limitador;

    @Value("${nexadrop.storage.mirror-enabled:true}")
    private boolean mirrorEnabled;
    @Value("${nexadrop.storage.mirror-batch:50}")
    private int mirrorBatch;

    /**
     * Hilos que descargan a la vez.
     *
     * <p>Eran 8 fijos, y con lotes de 50 cada 8 segundos —más otros 50 de variantes en el mismo ciclo— el
     * origen deja de responder: el 25-ago-2026 fallaron así 4.835 imágenes seguidas contra alicdn, con
     * tiempo de espera agotado, mientras esas mismas URLs devolvían 200 al pedirlas de una en una. Bajar el
     * ritmo tarda más en drenar la cola, pero drena; la avalancha no drenaba nada.
     */
    @Value("${nexadrop.storage.mirror-concurrency:4}")
    private int mirrorConcurrency;
    /** Espera base entre reintentos de una imagen fallida; se duplica con cada intento. */
    @Value("${nexadrop.storage.mirror-retry-base-minutes:5}")
    private int retryBaseMinutes;
    /** Tope de intentos: pasado eso la imagen se da por perdida y deja de consumir lote. */
    @Value("${nexadrop.storage.mirror-retry-max-attempts:6}")
    private int retryMaxAttempts;
    /** Cuántas fallidas se examinan en cada barrido de reintento. */
    @Value("${nexadrop.storage.mirror-retry-batch:100}")
    private int retryBatch;
    /**
     * Cuántas se comprimen por vuelta en la segunda pasada.
     *
     * <p>Más bajo que el lote de espejado a propósito: comprimir es lo que consume CPU y memoria, y la
     * cola de compresión no corre prisa —la foto ya se está viendo—. Lo que no cabe en esta vuelta entra
     * en la siguiente.
     */
    @Value("${nexadrop.storage.compresion-batch:10}")
    private int compresionBatch;

    // Redirects NO automáticos: se siguen a mano validando cada salto (anti-SSRF). Un origen no puede
    // redirigir a una IP interna/metadata sin pasar de nuevo por assertPublicHttpUrl.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Hilos de descarga. No se crea en la declaración del campo porque ahí {@code mirrorConcurrency} vale
     * todavía 0 y el pool nacería con el número de hilos equivocado, que es justo lo que este ajuste viene a
     * controlar.
     */
    /**
     * Plazo del lote entero. Esperar un Future que no vuelve deja colgado al HILO DEL PLANIFICADOR, y
     * con él todas las demás tareas programadas. Pasó el 2-sep-2026 con el espejado de vídeo, que
     * seguía este mismo patrón: cuatro horas sin despachar el outbox y sin un solo error en el
     * registro. Lo que no cabe en el plazo se cancela y vuelve en el siguiente barrido.
     */
    private static final Duration PLAZO_DEL_LOTE = Duration.ofMinutes(10);

    private volatile ExecutorService pool;

    /**
     * Hilo aparte que espera al lote, para que el del planificador vuelva enseguida. No se reutiliza el
     * pool de descargas: ocuparía uno de sus hilos esperando a los otros.
     */
    /**
     * Sustituible en pruebas por uno que ejecute en el hilo que llama. Es un {@link Executor} y no un
     * {@code ExecutorService} justo por eso: la interfaz mínima permite pasarle {@code Runnable::run} y
     * comprobar el resultado del lote en la misma prueba, sin esperas ni relojes.
     */
    volatile Executor orquestador;

    /** El que se crea aquí, guardado aparte para poder cerrarlo al apagar. */
    private volatile ExecutorService orquestadorPropio;

    /** Impide que se solapen dos lotes cuando el anterior tarda más que el intervalo del barrido. */
    private final AtomicBoolean loteEnMarcha = new AtomicBoolean(false);

    /** El mismo cerrojo para la segunda pasada, y separado a propósito: comprimir y espejar son colas
     *  distintas y una no tiene por qué esperar a la otra. */
    private final AtomicBoolean compresionEnMarcha = new AtomicBoolean(false);

    private Executor orquestador() {
        Executor actual = orquestador;
        if (actual == null) {
            synchronized (this) {
                actual = orquestador;
                if (actual == null) {
                    ExecutorService creado = Executors.newSingleThreadExecutor();
                    orquestadorPropio = creado;
                    orquestador = creado;
                    actual = creado;
                }
            }
        }
        return actual;
    }

    /**
     * Devuelve el pool, creándolo la primera vez que hace falta.
     *
     * <p>Perezoso y no en un {@code @PostConstruct} porque las pruebas unitarias construyen el servicio con
     * {@code @InjectMocks} y ahí nadie invoca los ganchos del ciclo de vida de Spring: con la creación en el
     * gancho, el pool llegaba nulo y reventaba el primer lote.
     */
    private ExecutorService pool() {
        ExecutorService actual = pool;
        if (actual == null) {
            synchronized (this) {
                actual = pool;
                if (actual == null) {
                    actual = Executors.newFixedThreadPool(Math.max(1, mirrorConcurrency > 0 ? mirrorConcurrency : 4));
                    pool = actual;
                }
            }
        }
        return actual;
    }

    @PreDestroy
    void shutdown() {
        ExecutorService actual = pool;
        if (actual != null) {
            actual.shutdownNow();
        }
        ExecutorService orq = orquestadorPropio;
        if (orq != null) {
            orq.shutdownNow();
        }
    }

    /** Una vez por arranque: reencola las imágenes cuyo cdn_url no apunta al storage vigente. */
    private volatile boolean healedStaleUrls = false;

    /** Job: espeja un lote de imágenes PENDING. Drena importación + backfill con el tiempo. */
    @Scheduled(fixedDelayString = "${nexadrop.storage.mirror-interval-ms:8000}")
    public void mirrorPendingScheduled() {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        // Se encarga el lote y se VUELVE: el trabajo no se hace en el hilo del planificador. Ese hilo es
        // uno solo para las 18 tareas programadas —y con los hilos virtuales activados,
        // spring.task.scheduling.pool.size ni siquiera se aplica—, así que quedarse aquí esperando
        // descargas para el resto. El 3-sep-2026 fue lo que dejó cuatro horas sin despachar el outbox,
        // sin un solo error en el registro. El candado evita que se solapen dos lotes.
        if (!loteEnMarcha.compareAndSet(false, true)) {
            return;
        }
        orquestador().execute(() -> {
            try {
                if (!healedStaleUrls) {
                    healStaleCdnUrls();
                    healedStaleUrls = true; // oportunista: se marca aunque falle, no puede bloquear el job
                }
                mirrorPendingBatch(mirrorBatch);
                mirrorVariantImagesBatch(mirrorBatch);
            } catch (Exception e) {
                log.warn("Mirror: el lote terminó mal: {}", e.toString());
            } finally {
                loteEnMarcha.set(false);
            }
        });
    }

    /**
     * Job: devuelve a la cola las imágenes fallidas a las que ya les toca otro intento.
     *
     * <p>Antes solo las reencolaba el saneo del arranque, que corre una vez por proceso. Con eso, 415
     * productos se quedaron fuera del escaparate hasta el siguiente reinicio —y al reiniciar se
     * reintentaban todas a la vez, que es justo lo que las había tumbado—. Ahora se hace a ritmo lento y
     * cada imagen espera más que la anterior vez.
     */
    @Scheduled(fixedDelayString = "${nexadrop.storage.mirror-retry-interval-ms:300000}")
    public void requeueFailedScheduled() {
        requeueFailedForRetry(Instant.now());
    }

    /**
     * Reencola las fallidas cuya espera ya venció. El instante entra por parámetro para poder fijar en una
     * prueba qué se reintenta y qué no sin depender del reloj de la máquina.
     *
     * @param ahora momento contra el que se mide la espera de cada imagen
     */
    public void requeueFailedForRetry(Instant ahora) {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        // Primero las de variante: lo que sigue tiene un «return» temprano cuando no hay ninguna imagen de
        // producto lista, y colgarlas detrás las dejaba sin reintentar justo cuando la cola se vacía.
        requeueFailedVariantImages(ahora);
        List<ProductImageEntity> candidatas = imageRepository.findFailedForRetry(retryMaxAttempts, retryBatch);
        List<UUID> listas = candidatas.stream().filter(img -> esperaCumplida(img, ahora))
                .map(ProductImageEntity::getId).toList();
        if (listas.isEmpty()) {
            return;
        }
        imageRepository.requeueToPending(listas);
        log.info("Mirror reintento: {} de {} imágenes fallidas vuelven a la cola", listas.size(),
                candidatas.size());
    }

    /**
     * Devuelve a la cola las imágenes de VARIANTE y de MUESTRA DE COLOR que fallaron.
     *
     * <p>Iba aparte de las de producto porque estas no llevan contador de intentos —el comentario del
     * barrido decía «no hay estado FAILED para variantes, son pocas», y era falso: sí lo hay, y era
     * DEFINITIVO—. La consulta del barrido descarta lo marcado, el barrido pone la marca al fallar y
     * nadie la quitaba, así que una muestra que fallara una vez no se espejaba nunca más. El 18-sep-2026,
     * tras cargar 9.425 productos, el 96% de las muestras se quedó apuntando al proveedor, que responde
     * 403 a quien la enlaza desde otra web: en la ficha salían rotas.
     *
     * <p>La espera es la misma base que para las de producto, contada desde el fallo: recupera lo
     * pasajero —un tiempo de espera agotado durante una carga masiva, que es lo que pasó— sin repetir
     * la avalancha que lo provocó.
     */
    private void requeueFailedVariantImages(Instant ahora) {
        Instant antesDe = ahora.minus(Duration.ofMinutes(retryBaseMinutes));
        int valores = variantValueRepository.requeueFailed(antesDe, retryBatch);
        int variantes = variantRepository.requeueFailed(antesDe, retryBatch);
        if (valores + variantes > 0) {
            log.info("Mirror reintento: {} muestras de color y {} imágenes de variante vuelven a la cola",
                    valores, variantes);
        }
    }

    /**
     * ¿Le toca ya otro intento? Espera = base × 2^intentos, contada desde el último fallo.
     *
     * <p>Sin fecha de último intento se deja pasar: una imagen sin ese dato es anterior a este mecanismo y
     * no tiene sentido retenerla para siempre.
     */
    private boolean esperaCumplida(ProductImageEntity img, Instant ahora) {
        Instant ultimo = img.getUpdatedAt();
        if (ultimo == null) {
            return true;
        }
        long minutos = (long) retryBaseMinutes << Math.min(img.getMirrorAttempts(), 16);
        return !ultimo.plus(Duration.ofMinutes(minutos)).isAfter(ahora);
    }

    /**
     * Auto-heal (1 vez/arranque): si hay cdn_url de otro entorno (p.ej. localhost tras cambiar a un MinIO
     * nuevo) o muertos, se reencolan a PENDING para re-espejar con la URL pública vigente.
     */
    private void healStaleCdnUrls() {
        try {
            // (a) cdn_url de otro entorno/dominio o nulo → reencolar.
            int n = imageRepository.requeueNotMirrored(storage.publicUrl() + "%");
            if (n > 0) {
                log.info("Mirror auto-heal: {} imágenes con cdn_url no-vigente reencoladas a PENDING", n);
            }
            requeueMirroredWithoutObject();
        } catch (Exception e) {
            log.warn("Mirror auto-heal falló: {}", e.getMessage());
        }
    }

    /**
     * (b) cdn_url con nuestro dominio pero cuyo OBJETO ya no existe (p.ej. MinIO reseteado): listamos las
     * claves reales del bucket y reencolamos las que falten. Si el listado viene vacío no se toca nada: un
     * bucket que no responde no puede confundirse con un bucket sin objetos y reencolarlo TODO.
     */
    private void requeueMirroredWithoutObject() {
        Set<String> keys = storage.listKeys();
        if (keys.isEmpty()) {
            return;
        }
        String base = Texts.stripTrailingSlashes(storage.publicUrl()) + "/";
        int missing = 0;
        for (ProductImageEntity img : imageRepository
                .findByMirrorStatusAndCdnUrlStartingWith(MirrorStatus.MIRRORED, base)) {
            String cdn = img.getCdnUrl();
            String key = cdn.length() > base.length() ? cdn.substring(base.length()) : "";
            if (!keys.contains(key)) {
                imageRepository.markStatus(img.getId(), MirrorStatus.PENDING);
                missing++;
            }
        }
        if (missing > 0) {
            log.info("Mirror auto-heal: {} imágenes MIRRORED con objeto inexistente reencoladas", missing);
        }
    }


    /**
     * Espeja a storage las imágenes de las VARIANTES y de los VALORES de eje (p.ej. la foto de cada
     * color) que aún apuntan al origen. La capa de vista ({@code pickVariantImage}/{@code pickValueImage})
     * ya prefiere {@code image_cdn_url}, así que basta con poblarla. Lote acotado; los orígenes muertos
     * se reintentan en ciclos posteriores (no hay estado FAILED para variantes, son pocas).
     */
    public void mirrorVariantImagesBatch(int limit) {
        if (!storage.isReady()) {
            return;
        }
        // publicUrl() habla con el almacenamiento y puede fallar. Estaba fuera de todo try, así que un
        // almacenamiento caído no dejaba «cero imágenes espejadas» sino la excepción subiendo por el
        // planificador y ese ciclo entero perdido, incluidas las tareas que van detrás.
        String prefix;
        try {
            prefix = Texts.stripTrailingSlashes(storage.publicUrl()) + "%";
        } catch (RuntimeException e) {
            log.warn("Mirror de imágenes de variante omitido: el almacenamiento no responde ({})", e.toString());
            return;
        }
        PageRequest top = PageRequest.of(0, Math.max(1, limit));
        /*
         * EN PARALELO, igual que las fotos de producto.
         *
         * <p>Este bucle iba de una en una, y el ajuste de concurrencia no lo tocaba: el pool solo lo usaba
         * el barrido de fotos de producto. Medido en PRE el 18-sep-2026, tras cargar 9.425 productos:
         * 93.448 muestras de color con origen, 9.351 espejadas, avanzando a ~510 por hora. Casi SIETE DÍAS
         * para las que faltaban, con las fichas enseñando el hueco mientras tanto —el proveedor responde
         * 403 a quien enlaza sus imágenes desde otra web—.
         *
         * <p>Y no era falta de máquina: el nodo al 16% de CPU y el pod sin límite. El coste es espera de
         * red, que es exactamente lo que se paraleliza bien. Se usa el MISMO pool que las fotos para que
         * el número de descargas a la vez siga siendo uno solo y ajustable por entorno: dos pools con dos
         * ajustes distintos es la clase de cosa que hace que bajar un número no frene nada.
         */
        int ok = 0;
        ok += espejaEnParalelo(variantRepository.findNeedingImageMirror(prefix, top),
                ProductVariantEntity::getId, ProductVariantEntity::getImageSourceUrl,
                variantRepository::markImageCdn, variantRepository::markImageFailed, "variante");
        ok += espejaEnParalelo(variantValueRepository.findNeedingImageMirror(prefix, top),
                VariantValueEntity::getId, VariantValueEntity::getImageSourceUrl,
                variantValueRepository::markImageCdn, variantValueRepository::markImageFailed, "valor");
        if (ok > 0) {
            log.info("Mirror imágenes de variante/valor: {} subidas a storage", ok);
        }
    }

    /**
     * Descarga y guarda un grupo de imágenes a la vez, y anota el resultado de cada una.
     *
     * <p>Sirve para variantes y para valores de eje porque lo único que cambia entre las dos es de dónde
     * sale el identificador y a qué repositorio se le cuenta el final. El fallo de una NO corta el grupo:
     * se marca y se sigue, que es lo que impide que una foto muerta deje sin espejar a las demás.
     */
    private <T> int espejaEnParalelo(List<T> filas, Function<T, UUID> id, Function<T, String> origen,
            BiConsumer<UUID, String> alLograrlo, BiConsumer<UUID, Instant> alFallar, String que) {
        if (filas.isEmpty()) {
            return 0;
        }
        // Estas SÍ se comprimen en el acto, al revés que las fotos de producto, y es deliberado.
        //
        // Lo que se gana separando la compresión es que la foto aparezca antes, y eso pesa cuando la
        // imagen es grande: una foto de producto tarda en bajarse y más en comprimirse. Una muestra de
        // color es un recuadro pequeño; comprimirla cuesta poco y no retrasa nada apreciable.
        //
        // A cambio se evita tener que mantener una segunda cola para ellas: la compresión diferida se
        // apoya en `product_image.comprimida_en`, y variantes y valores de eje viven en otras tablas
        // que tendrían que ganar su propia columna, su propio índice y su propio barrido. Trabajo y
        // superficie de fallo a cambio de casi nada.
        //
        // Y el motivo por el que comprimir aquí ya no es peligroso: esto corre en `backend-espejado`,
        // su propio despliegue. Si el codificador WebP nativo se lleva la máquina virtual por delante
        // —que es lo que pasó el 5-sep-2026—, cae un trabajador reponible, no el escaparate.
        List<Future<String>> pedidos = filas.stream()
                .map(fila -> pool().submit(() -> fetchAndStore(origen.apply(fila), true).url())).toList();
        int ok = 0;
        for (int i = 0; i < pedidos.size(); i++) {
            UUID cual = id.apply(filas.get(i));
            try {
                alLograrlo.accept(cual, pedidos.get(i).get());
                ok++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Al interrumpir se cancela lo que quede en vuelo: sin esto, el apagado espera a que
                // terminen descargas que ya no le importan a nadie.
                pedidos.subList(i, pedidos.size()).forEach(f -> f.cancel(true));
                alFallar.accept(cual, Instant.now());
                return ok;
            } catch (ExecutionException e) {
                log.debug("Mirror imagen de {} {} falló ({}): {}", que, cual, origen.apply(filas.get(i)),
                        e.getCause() != null ? e.getCause().toString() : e.toString());
                alFallar.accept(cual, Instant.now());
            }
        }
        return ok;
    }

    /** Procesa hasta {@code limit} imágenes PENDING en paralelo. Devuelve cuántas se espejaron. */
    public int mirrorPendingBatch(int limit) {
        List<ProductImageEntity> pending = imageRepository
                .findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING);
        if (pending.isEmpty()) {
            return 0;
        }
        if (pending.size() > limit) {
            pending = pending.subList(0, limit);
        }
        List<Future<Boolean>> futures = pending.stream()
                .map(img -> pool().submit(() -> mirrorOne(img.getId(), img.getSourceUrl()))).toList();
        int ok = 0;
        List<UUID> mirroredImageIds = new ArrayList<>();
        Instant limiteLote = Instant.now().plus(PLAZO_DEL_LOTE);
        for (int i = 0; i < futures.size(); i++) {
            try {
                long queda = Duration.between(Instant.now(), limiteLote).toMillis();
                if (queda <= 0) {
                    futures.get(i).cancel(true);
                    continue;
                }
                if (Boolean.TRUE.equals(futures.get(i).get(queda, TimeUnit.MILLISECONDS))) {
                    ok++;
                    mirroredImageIds.add(pending.get(i).getId());
                }
            } catch (TimeoutException ex) {
                futures.get(i).cancel(true);
                log.warn("Mirror: se agotó el plazo del lote, lo que falte va al siguiente barrido");
            } catch (InterruptedException ex) {
                // Tragarse la interrupción deja al pool sin enterarse de que le han pedido parar.
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                log.debug("Mirror falló imagen {}: {}", pending.get(i).getId(), ex.toString());
                // el estado FAILED de la imagen ya se marca dentro de mirrorOne
            }
        }
        // Reindexar en OpenSearch los productos cuyas imágenes acaban de espejarse: su flag hasImage pasa a
        // true y así aparecen en el escaparate SIN esperar a un reindexado manual (convención: cada cambio
        // reindexa). Un producto por id afectado (se deduplican en la query).
        reindexAffectedProducts(mirroredImageIds);
        log.info("Mirror imágenes: {}/{} subidas a storage (~{} PENDING restantes)", ok, pending.size(),
                imageRepository.countByMirrorStatus(MirrorStatus.PENDING));
        return ok;
    }

    /**
     * Espeja YA (en background) las imágenes PENDING de unos productos recién importados y los reindexa, para
     * que aparezcan en el escaparate casi al instante sin esperar al ciclo programado. No bloquea el import.
     */
    @Async
    public void mirrorProductsAsync(List<UUID> productIds) {
        if (!mirrorEnabled || productIds == null || productIds.isEmpty()) {
            return;
        }
        mirrorProductImagesOf(productIds);
        mirrorVariantImagesOf(productIds);
    }

    /** Las fotos de producto de unos productos recién importados. */
    private void mirrorProductImagesOf(List<UUID> productIds) {
        List<ProductImageEntity> imgs = imageRepository.findByProductIdInAndMirrorStatus(productIds,
                MirrorStatus.PENDING);
        if (imgs.isEmpty()) {
            return;
        }
        List<Future<Boolean>> futures = imgs.stream()
                .map(img -> pool().submit(() -> mirrorOne(img.getId(), img.getSourceUrl()))).toList();
        List<UUID> mirroredImageIds = new ArrayList<>();
        Instant limiteLote = Instant.now().plus(PLAZO_DEL_LOTE);
        for (int i = 0; i < futures.size(); i++) {
            try {
                long queda = Duration.between(Instant.now(), limiteLote).toMillis();
                if (queda <= 0) {
                    futures.get(i).cancel(true);
                    continue;
                }
                if (Boolean.TRUE.equals(futures.get(i).get(queda, TimeUnit.MILLISECONDS))) {
                    mirroredImageIds.add(imgs.get(i).getId());
                }
            } catch (TimeoutException ex) {
                futures.get(i).cancel(true);
                log.warn("Mirror variantes: se agotó el plazo del lote, lo que falte va al siguiente barrido");
            } catch (InterruptedException ex) {
                // Tragarse la interrupción deja al pool sin enterarse de que le han pedido parar.
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                log.debug("Mirror falló imagen {}: {}", imgs.get(i).getId(), ex.toString());
                // el estado FAILED de la imagen ya se marca dentro de mirrorOne
            }
        }
        reindexAffectedProducts(mirroredImageIds);
        log.info("Mirror import: {}/{} imágenes de {} producto(s) espejadas al importar", mirroredImageIds.size(),
                imgs.size(), productIds.size());
    }

    /**
     * Las imágenes de VARIANTE y las MUESTRAS DE COLOR de unos productos recién importados.
     *
     * <p>Por qué existe: las fotos de producto ya se espejaban al importar y estas no —solo las recogía
     * el barrido periódico—, y esa asimetría no se nota hasta que hay una carga masiva en marcha. Ahí el
     * barrido se queda sin turno, porque el espejado de la importación ocupa el mismo grupo de hilos, y
     * las muestras de color se quedan enseñando el hueco: el proveedor responde 403 a quien enlaza sus
     * imágenes desde otra web, así que el comprador ve un icono roto donde debería elegir el color.
     *
     * <p>Medido en PRE el 18-sep-2026 con la carga corriendo: en diez minutos las fotos de producto
     * pasaron de 324 a 625 espejadas y las variantes se quedaron clavadas en 25 —un solo lote, el
     * primero—, con cero fallos registrados. No fallaban: no les tocaba turno.
     */
    private void mirrorVariantImagesOf(List<UUID> productIds) {
        if (!storage.isReady()) {
            return;
        }
        String prefix;
        try {
            prefix = Texts.stripTrailingSlashes(storage.publicUrl()) + "%";
        } catch (RuntimeException e) {
            log.warn("Mirror de variantes al importar omitido: el almacenamiento no responde ({})", e.toString());
            return;
        }
        int ok = espejaEnParalelo(variantRepository.findNeedingImageMirrorByProducts(prefix, productIds),
                ProductVariantEntity::getId, ProductVariantEntity::getImageSourceUrl,
                variantRepository::markImageCdn, variantRepository::markImageFailed, "variante");
        ok += espejaEnParalelo(variantValueRepository.findNeedingImageMirrorByProducts(prefix, productIds),
                VariantValueEntity::getId, VariantValueEntity::getImageSourceUrl,
                variantValueRepository::markImageCdn, variantValueRepository::markImageFailed, "valor");
        if (ok > 0) {
            log.info("Mirror import: {} imágenes de variante y muestra de color de {} producto(s) espejadas "
                    + "al importar", ok, productIds.size());
        }
    }

    /**
     * Pase completo (en background): drena TODAS las imágenes PENDING en lotes hasta agotarlas. Se dispara
     * al reindexar desde el admin, para que "reindexar" deje también todas las imágenes espejadas y visibles
     * (cada lote reindexa sus productos vía {@link #reindexAffectedProducts}). Acotado por nº de rondas.
     */
    @Async
    public void mirrorAllPendingAsync() {
        if (!mirrorEnabled) {
            return;
        }
        int rounds = 0;
        while (imageRepository.countByMirrorStatus(MirrorStatus.PENDING) > 0 && rounds++ < 500) {
            mirrorPendingBatch(mirrorBatch);
        }
        log.info("Mirror: pase completo tras reindex terminado ({} rondas)", rounds);
    }

    /**
     * Devuelve a la cola un lote de imágenes guardadas SIN comprimir, para aligerar el histórico.
     *
     * <p>Hace falta porque la compresión solo actúa al espejar: las que ya estaban guardadas no se tocan
     * solas, y reindexar tampoco las alcanza —el barrido solo mira las que están pendientes, y estas
     * constan como hechas—. Esto las marca como pendientes otra vez para que vuelvan a pasar por el
     * compresor.
     *
     * <p>Devuelve cuántas se reencolaron y cuántas quedan, para poder ir dando lotes hasta terminar sin
     * tener que adivinar cuánto falta.
     */
    @Transactional
    public ReencoladoParaComprimir reencolarParaComprimir(int limite) {
        int reencoladas = imageRepository.reencolarSinComprimir(Math.max(1, Math.min(limite, 2000)));
        long quedan = imageRepository.countByMirrorStatusAndWidthIsNull(MirrorStatus.MIRRORED);
        log.info("Compresión del histórico: {} imágenes vuelven a la cola, quedan {} sin comprimir",
                reencoladas, quedan);
        return new ReencoladoParaComprimir(reencoladas, quedan);
    }

    /** Cuántas se han devuelto a la cola en este lote y cuántas siguen sin comprimir. */
    public record ReencoladoParaComprimir(int reencoladas, long pendientes) {
    }

    /**
     * Cuánto falta para tener el histórico comprimido.
     *
     * <p>{@code enCola} es lo que el espejador está procesando AHORA. El panel lo necesita para encadenar
     * lotes sin amontonarlos: pedir otro lote con la cola todavía llena no acelera nada —el espejador va a
     * su ritmo— y sí deja miles de imágenes marcadas como pendientes, que es el estado en el que una caída
     * del proceso hace más daño.
     */
    @Transactional(readOnly = true)
    public EstadoDeCompresion estadoDeCompresion() {
        return new EstadoDeCompresion(imageRepository.countByMirrorStatusAndWidthIsNull(MirrorStatus.MIRRORED),
                imageRepository.countByMirrorStatus(MirrorStatus.PENDING));
    }

    /** Lo que queda por comprimir y lo que el espejador tiene ahora mismo entre manos. */
    public record EstadoDeCompresion(long pendientes, long enCola) {
    }

    /** Reindexa los productos afectados por un lote de imágenes recién espejadas (hasImage → true). */
    private void reindexAffectedProducts(List<UUID> mirroredImageIds) {
        if (mirroredImageIds.isEmpty()) {
            return;
        }
        for (UUID productId : imageRepository.findProductIdsByImageIds(mirroredImageIds)) {
            try {
                productIndexer.indexProduct(productId);
            } catch (Exception e) {
                log.debug("Reindex tras espejar falló para producto {}: {}", productId, e.toString());
            }
        }
    }

    /**
     * Visible para las pruebas del mismo paquete, igual que {@code orquestador}: es el camino donde se
     * decide si una imagen se reaprovecha o se descarga, y probarlo desde fuera exigiría red real.
     */
    boolean mirrorOne(UUID id, String src) {
        if (src == null || src.isBlank()) {
            // Sin origen no hay nada que reintentar, pero se cuenta igual: así agota sus intentos y deja de
            // aparecer en cada barrido ocupando el sitio de una que sí se puede recuperar.
            imageRepository.markFailedAndCountAttempt(id);
            return false;
        }
        // El intento se anota AQUÍ, antes de tocar nada, y se compromete en el acto. Si lo que viene
        // después mata el proceso —y comprimir puede: el codificador WebP es código nativo y un SIGSEGV
        // no se puede capturar—, la cuenta ya está guardada y esta imagen no volverá para siempre.
        imageRepository.anotaIntentoAntesDeProcesar(id);

        // 1) ¿ESTA URL YA SE BAJÓ ALGUNA VEZ? Entonces no se vuelve a bajar.
        //
        // El almacenamiento ya deduplica por contenido, pero eso ahorra DISCO: para calcular el hash del
        // contenido hay que haber descargado el fichero. Lo escaso no es el disco, es el proveedor —que
        // limita por tasa y responde 403 a quien enlaza desde fuera—. Un vendedor reutiliza su tabla de
        // tallas, su foto de material y su foto de embalaje en decenas de fichas: todas esas son la
        // misma descarga repetida.
        for (String candidate : candidateUrls(src)) {
            Optional<ImagenOrigenEspejadaEntity> conocida = origenesEspejados.findById(hashDeUrl(candidate));
            if (conocida.isPresent()) {
                ImagenOrigenEspejadaEntity y = conocida.get();
                imageRepository.markMirrored(id, y.getCdnUrl(), y.getBytes(), y.getHash(), y.getAncho(),
                        y.getAlto(), MirrorStatus.MIRRORED, Instant.now());
                imageRepository.resetAttempts(id);
                if (y.isComprimida()) {
                    // Ya estaba comprimida en su día: no hay nada que hacer en la segunda pasada.
                    imageRepository.marcaSinComprimir(id, Instant.now());
                }
                origenesEspejados.anotaUso(y.getUrlHash(), Instant.now());
                return true;
            }
        }

        // 2) Hay que bajarla. SIN COMPRIMIR: comprimir es la segunda pasada.
        //
        // Comprimir aquí era lo caro y lo peligroso del camino: caro porque decodificar un JPEG grande
        // cuesta CPU y memoria, y peligroso porque el codificador WebP es nativo y un SIGSEGV no se
        // puede capturar —el 5-sep-2026 mató la JVM y dejó las dos réplicas de PRE cayendo en bucle—.
        // Separándolo, el comprador ve la foto en cuanto está guardada, y el ahorro de disco llega
        // después sobre una imagen que ya se está sirviendo.
        for (String candidate : candidateUrls(src)) {
            try {
                Stored s = fetchAndStore(candidate, false);
                imageRepository.markMirrored(id, s.url(), s.bytes(), s.hash(), enteroONulo(s.ancho()),
                        enteroONulo(s.alto()), MirrorStatus.MIRRORED, Instant.now());
                imageRepository.resetAttempts(id);
                recuerdaOrigen(candidate, s, false);
                return true;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.debug("Mirror falló imagen {} ({}): {}", id, candidate, e.toString());
            }
        }
        imageRepository.markFailed(id);
        return false;
    }

    /**
     * SEGUNDA PASADA: comprime lo que ya está espejado.
     *
     * <p>Corre aparte del espejado y a su propio ritmo porque las dos tareas no tienen la misma
     * urgencia. Espejar es urgente: hasta que no está, la ficha enseña un hueco —el proveedor responde
     * 403 a quien enlaza sus imágenes desde otra web—. Comprimir no: la foto ya se ve, y lo único que
     * está en juego es cuánto ocupa.
     *
     * <p>Y comprimir es lo arriesgado. El codificador WebP es código nativo: un SIGSEGV suyo no se
     * puede capturar y se lleva la máquina virtual entera —pasó el 5-sep-2026 y dejó las dos réplicas
     * de preproducción cayendo en bucle—. Que eso ocurra sobre una imagen que YA se está sirviendo, y
     * no sobre una que todavía no existe, cambia por completo lo que se pierde.
     */
    @Scheduled(fixedDelayString = "${nexadrop.storage.compresion-interval-ms:30000}")
    public void comprimirPendientesScheduled() {
        if (!mirrorEnabled || !storage.isReady() || !compresionEnMarcha.compareAndSet(false, true)) {
            return;
        }
        orquestador().execute(() -> {
            try {
                comprimirPendientesBatch(compresionBatch);
            } catch (Exception e) {
                log.warn("Compresión diferida: el lote terminó mal: {}", e.toString());
            } finally {
                compresionEnMarcha.set(false);
            }
        });
    }

    /**
     * Comprime un lote de imágenes ya espejadas y deja la más pequeña de las dos.
     *
     * <p>Se queda con la original si comprimir no la aligera. Pasa con las fotos ya optimizadas en
     * origen: reescribirlas costaría una subida y dejaría el fichero IGUAL o mayor, además de cambiar
     * su URL sin motivo —y una URL que cambia invalida lo que el borde tuviera guardado—.
     *
     * @return cuántas se aligeraron de verdad
     */
    int comprimirPendientesBatch(int limite) {
        List<ProductImageEntity> pendientes = imageRepository.findPendientesDeComprimir(
                PageRequest.of(0, Math.max(1, limite)));
        if (pendientes.isEmpty()) {
            return 0;
        }
        int aligeradas = 0;
        long bytesAntes = 0;
        long bytesDespues = 0;
        for (ProductImageEntity img : pendientes) {
            try {
                byte[] original = storage.bytesFromPublicUrl(img.getCdnUrl());
                if (original == null || original.length == 0) {
                    imageRepository.marcaSinComprimir(img.getId(), Instant.now());
                    continue;
                }
                String tipo = sniffRasterImage(original);
                CompresorDeImagen.Comprimida lista = compresor.comprimir(original, tipo);
                if (lista.datos().length >= original.length) {
                    // Comprimir no la aligera: se deja como está y se saca de la cola.
                    imageRepository.marcaSinComprimir(img.getId(), Instant.now());
                    continue;
                }
                String hash = sha256(lista.datos());
                String key = "media/" + hash.substring(0, 2) + "/" + hash + "." + lista.tipo();
                String url = storage.upload(key, lista.datos(), lista.contentType());
                imageRepository.marcaComprimida(img.getId(), url, (long) lista.datos().length, hash,
                        Instant.now());
                bytesAntes += original.length;
                bytesDespues += lista.datos().length;
                aligeradas++;
            } catch (Exception e) {
                // Que una imagen no se deje comprimir no es una avería: se queda como está y sale de la
                // cola, porque reintentarla eternamente ocuparía el sitio de las que sí se pueden.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.debug("No se pudo comprimir la imagen {}: {}", img.getId(), e.toString());
                imageRepository.marcaSinComprimir(img.getId(), Instant.now());
            }
        }
        if (aligeradas > 0) {
            log.info("Compresión diferida: {} de {} aligeradas, {} kB -> {} kB (quedan {} por comprimir)",
                    aligeradas, pendientes.size(), bytesAntes / 1024, bytesDespues / 1024,
                    imageRepository.cuentaPendientesDeComprimir());
        }
        return aligeradas;
    }

    /** sha256 de la URL en hexadecimal: es la clave de la memoria de orígenes. */
    static String hashDeUrl(String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Esta máquina virtual no trae SHA-256", e);
        }
    }

    /**
     * Apunta que esta URL ya está bajada y dónde quedó.
     *
     * <p>Que esto falle no puede tumbar el espejado: la imagen ya está guardada y la ficha ya la
     * enseña. Lo único que se pierde es el ahorro de la próxima vez, así que se registra y se sigue.
     */
    private void recuerdaOrigen(String url, Stored s, boolean comprimida) {
        try {
            Instant ahora = Instant.now();
            origenesEspejados.save(ImagenOrigenEspejadaEntity.builder()
                    .urlHash(hashDeUrl(url))
                    .urlOrigen(url.length() > 800 ? url.substring(0, 800) : url)
                    .cdnUrl(s.url())
                    .bytes(s.bytes())
                    .hash(s.hash())
                    .ancho(enteroONulo(s.ancho()))
                    .alto(enteroONulo(s.alto()))
                    .comprimida(comprimida)
                    .creadaEn(ahora)
                    .usadaEn(ahora)
                    .veces(1)
                    .build());
        } catch (RuntimeException e) {
            log.debug("No se pudo recordar el origen {}: {}", url, e.toString());
        }
    }

    /**
     * URLs candidatas a descargar, en orden. Muchas imágenes de alicdn ibank se guardan SIN el sufijo
     * canónico {@code -0-cib.jpg} y devuelven 404; la variante con el sufijo sí resuelve (verificado). Se
     * prueba primero la original (las que ya funcionan siguen igual) y, si aplica, la variante -0-cib.jpg.
     */
    private List<String> candidateUrls(String src) {
        List<String> out = new ArrayList<>(2);
        out.add(src);
        if (src.contains("alicdn.com/img/ibank/") && src.endsWith(".jpg") && src.contains("_!!")
                && !src.endsWith("-0-cib.jpg")) {
            out.add(src.substring(0, src.length() - ".jpg".length()) + "-0-cib.jpg");
        }
        return out;
    }

    /** Resultado de subir una imagen al storage: URL pública navegable + metadatos para auditoría/dedup. */
    /** Un cero es «no se pudo averiguar», y eso se guarda como nulo: decir que una foto mide cero
     *  píxeles es peor que no decir nada, porque el navegador reservaría un hueco vacío. */
    private static Integer enteroONulo(int valor) {
        return valor > 0 ? valor : null;
    }

    /** Lo que quedó guardado. El ancho y el alto van a la base para que la ficha pueda reservar el hueco
     *  de la foto antes de que llegue: sin ellos, el texto salta cuando cada imagen termina de cargar. */
    private record Stored(String url, long bytes, String hash, int ancho, int alto) {
    }

    /**
     * Descarga la imagen de origen <b>sin Referer</b> (el HttpClient no lo añade → alicdn no la bloquea),
     * la sube al bucket con clave por content-hash (dedup) y devuelve la URL pública. Reutilizado por el
     * mirror de producto y de variante/valor.
     *
     * <p>Endurecido por seguridad:
     * <ul>
     *   <li><b>Anti-SSRF</b>: solo http/https a hosts que NO resuelvan a IP privada/loopback/link-local/
     *       metadata (169.254.169.254, etc.); los redirects se siguen a mano revalidando cada salto.</li>
     *   <li><b>Anti-XSS por content-type</b>: se verifican los <b>magic bytes</b> y solo se almacenan
     *       imágenes ráster reales (jpg/png/webp/gif) con su content-type correcto. Un SVG/HTML (aunque el
     *       origen mienta en el header) se descarta y NUNCA entra al bucket → no se puede servir ni ejecutar.</li>
     * </ul>
     */
    private Stored fetchAndStore(String src, boolean comprimir) throws IOException, InterruptedException {
        // La descarga pide turno al proveedor de esta URL. El techo es por host: nuestra máquina no es
        // el límite —se midió el nodo al 16% de CPU mientras el espejado iba a 4.200 imágenes/hora—,
        // lo es 1688, que limita por tasa y corta a quien insiste. Sin este turno, subir la concurrencia
        // deja de ser un problema de rendimiento y pasa a ser uno de acceso.
        HttpResponse<byte[]> res;
        try {
            res = limitador.conPermiso(src, () -> fetchFollowingRedirects(src.trim(), 5));
        } catch (IOException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
        byte[] data = res.body();
        if (res.statusCode() / 100 != 2 || data == null || data.length == 0) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        String type = sniffRasterImage(data); // jpg/png/webp/gif o lanza (descarta SVG/HTML/otros)

        // El sniff va ANTES de comprimir, y es importante que siga así: lo que se decodifica aquí ya se
        // ha comprobado que es una imagen ráster de verdad. Comprimir primero significaría abrir con el
        // decodificador algo que todavía no se sabe qué es.
        CompresorDeImagen.Comprimida lista = comprimir
                ? compresor.comprimir(data, type)
                : compresor.sinComprimir(data, type);

        // El hash se calcula sobre lo que se GUARDA, no sobre lo descargado: es la clave del fichero en
        // el almacén y sirve para no subir dos veces lo mismo. Con el hash del original, dos ajustes
        // distintos de compresión escribirían en la misma clave y el segundo no llegaría a subirse.
        String hash = sha256(lista.datos());
        String key = "media/" + hash.substring(0, 2) + "/" + hash + "." + lista.tipo();
        return new Stored(storage.upload(key, lista.datos(), lista.contentType()), lista.datos().length, hash,
                lista.ancho(), lista.alto());
    }

    /** Sigue redirects MANUALMENTE (máx {@code maxHops}), validando cada URL contra SSRF antes de pedirla. */
    private HttpResponse<byte[]> fetchFollowingRedirects(String url, int maxHops)
            throws IOException, InterruptedException {
        String current = url;
        for (int hop = 0; hop <= maxHops; hop++) {
            URI uri = URI.create(current);
            assertPublicHttpUrl(uri);
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; NX036ImageMirror/1.0)")
                    .timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 == 3) {
                String loc = res.headers().firstValue("location").orElse(null);
                if (loc == null) {
                    return res;
                }
                current = uri.resolve(loc).toString(); // resuelve también redirects relativos
                continue;
            }
            return res;
        }
        throw new IllegalStateException("Demasiados redirects para " + url);
    }

    /**
     * Anti-SSRF. Delega en {@link PublicHttpUrl}, que es donde vive esta comprobación para todo el que
     * llama a una dirección que ha registrado un tercero: aquí las imágenes del proveedor, y en los
     * despachadores de webhooks las direcciones de los partners.
     */
    static void assertPublicHttpUrl(URI uri) throws UnknownHostException {
        PublicHttpUrl.assertPublic(uri);
    }

    /**
     * Devuelve la extensión/tipo ({@code jpg|png|webp|gif}) según los <b>magic bytes</b> reales del
     * contenido; lanza si NO es una imagen ráster soportada. Así un SVG/HTML disfrazado de imagen (o un
     * content-type mentido por el origen) se descarta y nunca llega al bucket.
     */
    static String sniffRasterImage(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF) {
            return "jpg";
        }
        if (d.length >= 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "gif";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "webp";
        }
        throw new IllegalStateException("Contenido no es imagen ráster soportada (jpg/png/webp/gif) — descartado");
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return Long.toHexString(Arrays.hashCode(data) & 0xffffffffL);
        }
    }
}
