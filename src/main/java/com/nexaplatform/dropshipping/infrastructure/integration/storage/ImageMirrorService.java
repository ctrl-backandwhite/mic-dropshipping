package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final ObjectStorageService storage;

    @Value("${nexadrop.storage.mirror-enabled:true}")
    private boolean mirrorEnabled;
    @Value("${nexadrop.storage.mirror-batch:50}")
    private int mirrorBatch;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /** Job: espeja un lote de imágenes PENDING. Drena importación + backfill con el tiempo. */
    @Scheduled(fixedDelayString = "${nexadrop.storage.mirror-interval-ms:8000}")
    public void mirrorPendingScheduled() {
        if (!mirrorEnabled || !storage.isReady()) {
            return;
        }
        mirrorPendingBatch(mirrorBatch);
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
            // Descarga SIN Referer (el HttpClient no lo añade) → alicdn no la bloquea.
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(src.trim()))
                    .header("User-Agent", "Mozilla/5.0 (compatible; NX036ImageMirror/1.0)")
                    .timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = res.body();
            if (res.statusCode() / 100 != 2 || data == null || data.length == 0) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }
            String ct = res.headers().firstValue("content-type").orElse("image/jpeg");
            String hash = sha256(data);
            String key = "media/" + hash.substring(0, 2) + "/" + hash + extOf(ct, src);
            String url = storage.upload(key, data, ct);
            imageRepository.markMirrored(id, url, (long) data.length, hash, MirrorStatus.MIRRORED, Instant.now());
            return true;
        } catch (Exception e) {
            log.debug("Mirror falló imagen {} ({}): {}", id, src, e.toString());
            imageRepository.markStatus(id, MirrorStatus.FAILED);
            return false;
        }
    }

    private static String extOf(String contentType, String src) {
        String ct = contentType.toLowerCase();
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("gif")) {
            return ".gif";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        String s = src.toLowerCase();
        if (s.contains(".png")) {
            return ".png";
        }
        if (s.contains(".webp")) {
            return ".webp";
        }
        return ".jpg";
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return Long.toHexString(Arrays.hashCode(data) & 0xffffffffL);
        }
    }
}
