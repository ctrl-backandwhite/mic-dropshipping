package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Value("${nexadrop.storage.mirror-enabled:true}")
    private boolean mirrorEnabled;
    @Value("${nexadrop.storage.mirror-batch:50}")
    private int mirrorBatch;

    // Redirects NO automáticos: se siguen a mano validando cada salto (anti-SSRF). Un origen no puede
    // redirigir a una IP interna/metadata sin pasar de nuevo por assertPublicHttpUrl.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** Una vez por arranque: reencola las imágenes cuyo cdn_url no apunta al storage vigente. */
    private volatile boolean healedStaleUrls = false;

    /** Job: espeja un lote de imágenes PENDING. Drena importación + backfill con el tiempo. */
    @Scheduled(fixedDelayString = "${nexadrop.storage.mirror-interval-ms:8000}")
    public void mirrorPendingScheduled() {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        // Auto-heal (1 vez/arranque): si hay cdn_url de otro entorno (p.ej. localhost tras cambiar a
        // un MinIO nuevo) o muertos, se reencolan a PENDING para re-espejar con la URL pública vigente.
        if (!healedStaleUrls) {
            try {
                // (a) cdn_url de otro entorno/dominio o nulo → reencolar.
                int n = imageRepository.requeueNotMirrored(storage.publicUrl() + "%");
                if (n > 0) {
                    log.info("Mirror auto-heal: {} imágenes con cdn_url no-vigente reencoladas a PENDING", n);
                }
                // (b) cdn_url con nuestro dominio pero cuyo OBJETO ya no existe (p.ej. MinIO reseteado):
                //     listamos las claves reales del bucket y reencolamos las que falten.
                java.util.Set<String> keys = storage.listKeys();
                if (!keys.isEmpty()) {
                    String base = storage.publicUrl().replaceAll("/+$", "") + "/";
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
            } catch (Exception e) {
                log.warn("Mirror auto-heal falló: {}", e.getMessage());
            }
            healedStaleUrls = true;
        }
        mirrorPendingBatch(mirrorBatch);
        mirrorVariantImagesBatch(mirrorBatch);
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
        String prefix = storage.publicUrl().replaceAll("/+$", "") + "%";
        PageRequest top = PageRequest.of(0, Math.max(1, limit));
        int ok = 0;
        for (ProductVariantEntity v : variantRepository.findNeedingImageMirror(prefix, top)) {
            try {
                variantRepository.markImageCdn(v.getId(), fetchAndStore(v.getImageSourceUrl()).url());
                ok++;
            } catch (Exception e) {
                log.debug("Mirror imagen de variante {} falló ({}): {}", v.getId(), v.getImageSourceUrl(), e.toString());
                variantRepository.markImageFailed(v.getId(), Instant.now());
            }
        }
        for (VariantValueEntity vv : variantValueRepository.findNeedingImageMirror(prefix, top)) {
            try {
                variantValueRepository.markImageCdn(vv.getId(), fetchAndStore(vv.getImageSourceUrl()).url());
                ok++;
            } catch (Exception e) {
                log.debug("Mirror imagen de valor {} falló ({}): {}", vv.getId(), vv.getImageSourceUrl(), e.toString());
                variantValueRepository.markImageFailed(vv.getId(), Instant.now());
            }
        }
        if (ok > 0) {
            log.info("Mirror imágenes de variante/valor: {} subidas a storage", ok);
        }
    }

    /** Procesa hasta {@code limit} imágenes PENDING en paralelo. Devuelve cuántas se espejaron. */
    public int mirrorPendingBatch(int limit) {
        List<ProductImageEntity> pending = imageRepository
                .findTop100ByMirrorStatusOrderByCreatedAtAsc(MirrorStatus.PENDING);
        if (pending.isEmpty()) {
            return 0;
        }
        if (pending.size() > limit) {
            pending = pending.subList(0, limit);
        }
        List<Future<Boolean>> futures = pending.stream()
                .map(img -> pool.submit(() -> mirrorOne(img.getId(), img.getSourceUrl()))).toList();
        int ok = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (Boolean.TRUE.equals(f.get())) {
                    ok++;
                }
            } catch (Exception ignored) {
                // el fallo individual ya se marca FAILED dentro de mirrorOne
            }
        }
        log.info("Mirror imágenes: {}/{} subidas a storage (~{} PENDING restantes)", ok, pending.size(),
                imageRepository.countByMirrorStatus(MirrorStatus.PENDING));
        return ok;
    }

    private boolean mirrorOne(UUID id, String src) {
        if (src == null || src.isBlank()) {
            imageRepository.markStatus(id, MirrorStatus.FAILED);
            return false;
        }
        try {
            Stored s = fetchAndStore(src);
            imageRepository.markMirrored(id, s.url(), s.bytes(), s.hash(), MirrorStatus.MIRRORED, Instant.now());
            return true;
        } catch (Exception e) {
            log.debug("Mirror falló imagen {} ({}): {}", id, src, e.toString());
            imageRepository.markStatus(id, MirrorStatus.FAILED);
            return false;
        }
    }

    /** Resultado de subir una imagen al storage: URL pública navegable + metadatos para auditoría/dedup. */
    private record Stored(String url, long bytes, String hash) {
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
    private Stored fetchAndStore(String src) throws Exception {
        HttpResponse<byte[]> res = fetchFollowingRedirects(src.trim(), 5);
        byte[] data = res.body();
        if (res.statusCode() / 100 != 2 || data == null || data.length == 0) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        String type = sniffRasterImage(data); // jpg/png/webp/gif o lanza (descarta SVG/HTML/otros)
        String contentType = "image/" + ("jpg".equals(type) ? "jpeg" : type);
        String hash = sha256(data);
        String key = "media/" + hash.substring(0, 2) + "/" + hash + "." + type;
        return new Stored(storage.upload(key, data, contentType), data.length, hash);
    }

    /** Sigue redirects MANUALMENTE (máx {@code maxHops}), validando cada URL contra SSRF antes de pedirla. */
    private HttpResponse<byte[]> fetchFollowingRedirects(String url, int maxHops) throws Exception {
        String current = url;
        for (int hop = 0; hop <= maxHops; hop++) {
            URI uri = URI.create(current);
            assertPublicHttpUrl(uri);
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; NX036ImageMirror/1.0)")
                    .timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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

    /** Anti-SSRF: rechaza esquemas no http(s) y hosts que resuelvan a IP no enrutable públicamente. */
    static void assertPublicHttpUrl(URI uri) throws Exception {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SecurityException("Esquema no permitido para descarga de imagen: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("URL de imagen sin host");
        }
        for (InetAddress addr : InetAddress.getAllByName(host)) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                throw new SecurityException("Host resuelve a IP no pública (posible SSRF): "
                        + host + " → " + addr.getHostAddress());
            }
            byte[] b = addr.getAddress();
            if (b.length == 4) { // rangos privados no cubiertos por isSiteLocalAddress
                int o0 = b[0] & 0xff;
                int o1 = b[1] & 0xff;
                if (o0 == 100 && o1 >= 64 && o1 <= 127) { // CGNAT 100.64.0.0/10
                    throw new SecurityException("Host en rango CGNAT (posible SSRF): " + addr.getHostAddress());
                }
            }
        }
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
