package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.translation.TranslationProvider;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "nexadrop.translation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TranslationService {

    private final TranslationProvider provider;
    private final StringRedisTemplate redis;
    private final ProductRepository productRepository;

    @Value("${nexadrop.translation.default-source:zh}")
    private String defaultSource;

    @Value("${nexadrop.translation.target-languages:es,en,pt}")
    private String targetLanguagesRaw;

    private List<String> targetLanguages() {
        return Arrays.stream(targetLanguagesRaw.split(",")).map(String::trim).toList();
    }

    @KafkaListener(topics = "product.ingested", groupId = "nexadrop-translation")
    @Transactional
    public void onProductIngested(com.nexaplatform.dropshipping.infrastructure.messaging.ProductIngestedEvent event) {
        translateProduct(event.productId());
    }

    @Transactional
    public void translateProduct(UUID productId) {
        Optional<ProductEntity> opt = productRepository.findById(productId);
        if (opt.isEmpty())
            return;
        ProductEntity p = opt.get();
        for (String lang : targetLanguages()) {
            try {
                String title = translateCached(p.getTitleZh(), defaultSource, lang);
                String shortDesc = translateCached(p.getShortDescriptionZh(), defaultSource, lang);
                String desc = translateCached(p.getDescriptionZh(), defaultSource, lang);
                upsertTranslation(p, lang, title, shortDesc, desc);
            } catch (Exception e) {
                log.warn("Translation failed for product {} -> {}: {}", productId, lang, e.getMessage());
            }
        }
        productRepository.save(p);
    }

    private void upsertTranslation(ProductEntity p, String lang, String title, String shortDesc, String desc) {
        Optional<ProductTranslationEntity> existing = p.getTranslations().stream()
                .filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst();
        ProductTranslationEntity tr = existing.orElseGet(() -> {
            ProductTranslationEntity n = ProductTranslationEntity.builder().product(p).language(lang)
                    .provider(provider.name()).build();
            p.getTranslations().add(n);
            return n;
        });
        tr.setTitle(title);
        tr.setShortDescription(shortDesc);
        tr.setDescription(desc);
        tr.setProvider(provider.name());
    }

    private String translateCached(String text, String src, String tgt) {
        if (text == null || text.isBlank())
            return text;
        String key = "tr:" + tgt + ":" + sha1(text);
        String hit = redis.opsForValue().get(key);
        if (hit != null)
            return hit;
        String translated = provider.translate(text, src, tgt);
        if (translated != null)
            redis.opsForValue().set(key, translated, Duration.ofDays(90));
        return translated;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(s.getBytes()));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
