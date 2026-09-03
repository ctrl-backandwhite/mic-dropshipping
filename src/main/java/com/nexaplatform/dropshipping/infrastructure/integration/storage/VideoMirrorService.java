package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Espeja a nuestro almacenamiento el vídeo de la ficha, igual que {@link ImageMirrorService} hace con las
 * fotos.
 *
 * <p>Hasta ahora no se espejaba: la ficha llevaba la dirección de {@code cloud.video.taobao.com} tal cual
 * y era el navegador del comprador quien iba a pedírsela a Alibaba. Eso son tres problemas a la vez —el
 * día que ellos quiten el vídeo la ficha se queda sin él sin que nadie se entere, cada reproducción sale
 * de sus servidores en vez de nuestro borde, y le cuenta a un tercero quién está mirando qué—. Con el
 * vídeo en el bucket, la vista prefiere {@code videoCdnUrl} y el navegador ya no sale de nuestro dominio.
 *
 * <p>Es el mismo mecanismo que el de imágenes —cola en {@code PENDING}, barrido programado, reintentos que
 * se van espaciando— con tres diferencias que impone el tamaño del fichero: menos hilos, lotes más
 * pequeños y un tope de bytes, porque un vídeo pesa cien veces lo que una foto y se descarga entero en
 * memoria antes de subirlo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMirrorService {

    private final ProductRepository productRepository;
    private final ObjectStorageService storage;

    @Value("${nexadrop.storage.video-mirror-enabled:true}")
    private boolean mirrorEnabled;

    /** Lote pequeño: cincuenta vídeos por ciclo serían cientos de megas en vuelo a la vez. */
    @Value("${nexadrop.storage.video-mirror-batch:10}")
    private int mirrorBatch;

    /**
     * Hilos que descargan a la vez. Dos, y no cuatro como en las imágenes, por lo mismo que allí se bajó
     * de ocho a cuatro: con 4.835 imágenes el origen dejó de responder a la avalancha mientras contestaba
     * bien de una en una. Un vídeo ocupa la línea mucho más rato que una foto.
     */
    @Value("${nexadrop.storage.video-mirror-concurrency:2}")
    private int mirrorConcurrency;

    /**
     * Tope de tamaño. Lo que pasa de aquí no se trae: no es un vídeo de producto de quince segundos, y
     * traérselo entero a memoria para descubrirlo saldría caro. Se marca fallido y se deja en el origen.
     */
    @Value("${nexadrop.storage.video-mirror-max-bytes:52428800}")
    private long maxBytes;

    /** Espera base entre reintentos de un vídeo fallido; se duplica con cada intento. */
    @Value("${nexadrop.storage.video-mirror-retry-base-minutes:15}")
    private int retryBaseMinutes;

    /** Tope de intentos: pasado eso el vídeo se da por perdido y deja de consumir lote. */
    @Value("${nexadrop.storage.video-mirror-retry-max-attempts:5}")
    private int retryMaxAttempts;

    /** Cuántos fallidos se examinan en cada barrido de reintento. */
    @Value("${nexadrop.storage.video-mirror-retry-batch:50}")
    private int retryBatch;

    // Redirects NO automáticos: se siguen a mano validando cada salto contra SSRF. Y hacen falta: la
    // dirección de cloud.video.taobao.com es un redirector que devuelve un 302 a otra firmada con caducidad.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Lo que puede tardar un lote entero antes de que se le corte.
     *
     * <p>Con lotes de 10, dos hilos y 120 s de espera por vídeo, un lote normal tarda menos de diez
     * minutos. Este plazo no es para el caso normal: es el tope que garantiza que el hilo del
     * planificador SIEMPRE vuelve.
     */
    private static final Duration PLAZO_DEL_LOTE = Duration.ofMinutes(10);

    private volatile ExecutorService pool;

    /**
     * El pool se crea al primer uso, no en un gancho del ciclo de vida: las pruebas unitarias construyen
     * el servicio con {@code @InjectMocks} y ahí nadie invoca esos ganchos, así que el pool llegaría nulo.
     * Es la misma razón por la que {@link ImageMirrorService} lo hace así.
     */
    private ExecutorService pool() {
        ExecutorService actual = pool;
        if (actual == null) {
            synchronized (this) {
                actual = pool;
                if (actual == null) {
                    actual = Executors.newFixedThreadPool(Math.max(1, mirrorConcurrency > 0 ? mirrorConcurrency : 2));
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
    }

    /** Barrido: espeja un lote de los vídeos que están en cola. */
    @Scheduled(fixedDelayString = "${nexadrop.storage.video-mirror-interval-ms:30000}")
    public void mirrorPendingScheduled() {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        mirrorPendingBatch(mirrorBatch);
    }

    /** Barrido: devuelve a la cola los vídeos fallidos a los que ya les toca otro intento. */
    @Scheduled(fixedDelayString = "${nexadrop.storage.video-mirror-retry-interval-ms:600000}")
    public void requeueFailedScheduled() {
        requeueFailedForRetry(Instant.now());
    }

    /**
     * Reencola los fallidos cuya espera ya venció. El instante entra por parámetro para poder fijar en una
     * prueba qué se reintenta y qué no sin depender del reloj de la máquina.
     *
     * @param ahora momento contra el que se mide la espera de cada vídeo
     */
    public void requeueFailedForRetry(Instant ahora) {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        List<ProductEntity> candidatos = productRepository.findVideosFailedForRetry(retryMaxAttempts, retryBatch);
        List<UUID> listos = candidatos.stream().filter(p -> esperaCumplida(p, ahora))
                .map(ProductEntity::getId).toList();
        if (listos.isEmpty()) {
            return;
        }
        productRepository.requeueVideos(listos);
        log.info("Espejado de vídeo: {} de {} fallidos vuelven a la cola", listos.size(), candidatos.size());
    }

    /**
     * ¿Le toca ya otro intento? Espera = base × 2^intentos, contada desde el último fallo. Sin fecha se
     * deja pasar: un vídeo sin ese dato es anterior a este mecanismo y no tiene sentido retenerlo.
     */
    private boolean esperaCumplida(ProductEntity p, Instant ahora) {
        Instant ultimo = p.getUpdatedAt();
        if (ultimo == null) {
            return true;
        }
        int intentos = p.getVideoMirrorAttempts() == null ? 0 : p.getVideoMirrorAttempts();
        long minutos = (long) retryBaseMinutes << Math.min(intentos, 16);
        return !ultimo.plus(Duration.ofMinutes(minutos)).isAfter(ahora);
    }

    /** Espeja un lote de la cola. Devuelve cuántos se consiguieron. */
    public int mirrorPendingBatch(int limit) {
        List<ProductEntity> pendientes = productRepository
                .findTop50ByVideoMirrorStatusOrderByUpdatedAtDesc(MirrorStatus.PENDING);
        if (pendientes.isEmpty()) {
            return 0;
        }
        return mirrorAll(pendientes.subList(0, Math.min(limit, pendientes.size())));
    }

    /**
     * Espeja YA los vídeos de los productos indicados, sin esperar al barrido.
     *
     * <p>Es lo que se llama justo después de guardar un producto, para que el vídeo esté en nuestro
     * almacenamiento cuando alguien abra la ficha y no treinta segundos más tarde.
     */
    @Async
    public void mirrorProductsAsync(List<UUID> productIds) {
        if (!mirrorEnabled || !storage.isReady() || productIds == null || productIds.isEmpty()) {
            return;
        }
        mirrorAll(productRepository.findByIdInAndVideoMirrorStatus(List.copyOf(productIds), MirrorStatus.PENDING));
    }

    private int mirrorAll(List<ProductEntity> productos) {
        List<Future<Boolean>> tareas = new ArrayList<>(productos.size());
        for (ProductEntity p : productos) {
            UUID id = p.getId();
            String origen = p.getVideoUrl();
            tareas.add(pool().submit(() -> mirrorOne(id, origen)));
        }
        // El lote entero tiene un plazo, y se cuenta desde aquí. Sin esto, esperar un Future que no
        // vuelve deja colgado al HILO DEL PLANIFICADOR, que es UNO para las 18 tareas programadas:
        // pasó el 2-sep-2026 en preproducción, un vídeo no terminó y se pararon el despachador del
        // outbox, el espejado de imágenes y todo lo demás durante cuatro horas, sin un solo error en
        // el registro. Lo que no cabe en el plazo se cancela y se queda para el siguiente barrido.
        Instant limite = Instant.now().plus(PLAZO_DEL_LOTE);
        int ok = 0;
        for (Future<Boolean> t : tareas) {
            long queda = Duration.between(Instant.now(), limite).toMillis();
            try {
                if (queda <= 0) {
                    t.cancel(true);
                    continue;
                }
                ok += Boolean.TRUE.equals(t.get(queda, TimeUnit.MILLISECONDS)) ? 1 : 0;
            } catch (TimeoutException e) {
                t.cancel(true);
                log.warn("Espejado de vídeo: se agotó el plazo del lote, lo que falte va al siguiente barrido");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Espejado de vídeo falló: {}", e.toString());
            }
        }
        if (ok > 0) {
            log.info("Espejado de vídeo: {} de {} subidos, quedan {} en cola", ok, productos.size(),
                    productRepository.countByVideoMirrorStatus(MirrorStatus.PENDING));
        }
        return ok;
    }

    private boolean mirrorOne(UUID id, String origen) {
        if (origen == null || origen.isBlank() || !origen.startsWith("http")) {
            // Sin un origen del que traerlo no hay nada que reintentar, pero se cuenta el intento igual:
            // así agota su cupo y deja de aparecer en cada barrido ocupando el sitio de uno recuperable.
            productRepository.markVideoFailedAndCountAttempt(id);
            return false;
        }
        try {
            Guardado g = fetchAndStore(origen.trim());
            productRepository.markVideoMirrored(id, g.url(), g.bytes(), g.hash(), MirrorStatus.MIRRORED,
                    Instant.now());
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Espejado de vídeo falló para {} ({}): {}", id, origen, e.toString());
            productRepository.markVideoFailedAndCountAttempt(id);
            return false;
        }
    }

    /** Resultado de subir un vídeo: dirección pública navegable + metadatos para auditar y no repetir. */
    private record Guardado(String url, long bytes, String hash) {
    }

    /**
     * Descarga el vídeo del origen y lo sube al bucket con clave por contenido.
     *
     * <p>Dos cosas que no están en el de imágenes y aquí son imprescindibles:
     *
     * <ul>
     *   <li><b>Se identifica como navegador.</b> Alibaba responde {@code 490 非法访问} —acceso ilegal— a
     *       quien no lo hace. Con la cabecera del mirror de imágenes, los vídeos NO se pueden traer: se
     *       comprobó, y las mismas direcciones que fallaban devolvían el vídeo con una de navegador.</li>
     *   <li><b>Tope de tamaño.</b> Lo que pasa del tope no se guarda.</li>
     * </ul>
     *
     * <p>Y dos que sí comparte, porque son las que impiden que esto se convierta en un agujero: cada salto
     * del redirector se revalida contra SSRF, y el contenido tiene que ser un MP4 de verdad según sus
     * primeros bytes, no según lo que diga la cabecera del origen.
     */
    private Guardado fetchAndStore(String origen) throws IOException, InterruptedException {
        HttpResponse<byte[]> res = fetchFollowingRedirects(origen, 5);
        byte[] datos = res.body();
        if (res.statusCode() / 100 != 2 || datos == null || datos.length == 0) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        if (datos.length > maxBytes) {
            throw new IllegalStateException("vídeo de " + (datos.length / 1024 / 1024) + " MB, por encima del tope");
        }
        if (!esMp4(datos)) {
            throw new IllegalStateException("el contenido no es un MP4");
        }
        String hash = sha256(datos);
        String key = "video/" + hash.substring(0, 2) + "/" + hash + ".mp4";
        return new Guardado(storage.upload(key, datos, "video/mp4"), datos.length, hash);
    }

    /** Sigue los redirects a mano, validando cada dirección antes de pedirla. */
    private HttpResponse<byte[]> fetchFollowingRedirects(String url, int maxSaltos)
            throws IOException, InterruptedException {
        String actual = url;
        for (int salto = 0; salto <= maxSaltos; salto++) {
            URI uri = URI.create(actual);
            PublicHttpUrl.assertPublic(uri);
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri)
                    .header("User-Agent", NAVEGADOR)
                    .timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() / 100 == 3) {
                String destino = res.headers().firstValue("location").orElse(null);
                if (destino == null) {
                    return res;
                }
                actual = uri.resolve(destino).toString();
                continue;
            }
            return res;
        }
        throw new IllegalStateException("Demasiados redirects para " + url);
    }

    /**
     * Alibaba solo sirve el vídeo a quien se identifica como navegador; a cualquier otro le responde
     * {@code 490 非法访问}. No es una preferencia: sin esto no hay vídeo que espejar.
     */
    static final String NAVEGADOR = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    /**
     * Si el contenido es un MP4 de verdad, mirando sus bytes y no lo que diga el origen.
     *
     * <p>Un MP4 empieza por el tamaño de su primera caja (4 bytes) y la etiqueta {@code ftyp}. Se comprueba
     * por lo mismo que en las imágenes se miran los magic bytes: lo que entra al bucket se sirve luego
     * desde nuestro dominio, y ahí no puede colarse un HTML porque el origen mienta en la cabecera.
     */
    static boolean esMp4(byte[] d) {
        return d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    private static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
