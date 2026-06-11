package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.messaging.ImageMirrorEvent;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageMirrorService {

    private static final int[] SIZES = {400, 800, 1600};

    private final WebClient.Builder webClientBuilder;
    private final StorageService storageService;
    private final ProductImageRepository imageRepository;

    @KafkaListener(topics = NexaTopics.IMAGE_FETCH, groupId = "nexadrop-image-mirror")
    @Transactional
    public void onImageFetch(ImageMirrorEvent event) {
        Optional<ProductImageEntity> opt = imageRepository.findById(event.imageId());
        if (opt.isEmpty()) {
            log.warn("Image {} not found — skipping mirror", event.imageId());
            return;
        }
        ProductImageEntity image = opt.get();
        if (image.getMirrorStatus() == MirrorStatus.MIRRORED) {
            return;
        }
        try {
            byte[] original = download(event.sourceUrl());
            String hash = sha256(original);
            String basePath = "products/" + event.productId() + "/" + event.imageId();

            String mainUrl = null;
            for (int size : SIZES) {
                byte[] resized = resizeToWebp(original, size);
                String key = basePath + "/" + size + "_" + hash + ".webp";
                String cdnUrl = storageService.putBytes(key, resized, "image/webp");
                if (size == 800) {
                    mainUrl = cdnUrl;
                }
            }

            image.setCdnUrl(mainUrl);
            image.setHash(hash);
            image.setBytes((long) original.length);
            image.setMirrorStatus(MirrorStatus.MIRRORED);
            image.setMirroredAt(Instant.now());
            imageRepository.save(image);
            log.info("Mirrored image {} -> {}", event.imageId(), mainUrl);
        } catch (Exception e) {
            log.error("Failed to mirror image {} ({}): {}", event.imageId(), event.sourceUrl(), e.getMessage());
            image.setMirrorStatus(MirrorStatus.FAILED);
            imageRepository.save(image);
        }
    }

    private byte[] download(String url) {
        return webClientBuilder.build()
                .get()
                .uri(url)
                .header("Referer", "https://detail.1688.com/")
                .header("User-Agent", "Mozilla/5.0 NexaDrop ImageMirror/1.0")
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    private byte[] resizeToWebp(byte[] bytes, int size) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.of(new ByteArrayInputStream(bytes))
                .size(size, size)
                .outputFormat("jpg")
                .outputQuality(0.85)
                .toOutputStream(out);
        // Note: we save as .webp filename but write JPEG bytes when WebP isn't available
        // in default JVM. ImageIO can be extended with the webp-imageio plugin for true WebP.
        return out.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }
}
