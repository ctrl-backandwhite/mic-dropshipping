package com.nexaplatform.dropshipping.infrastructure.cache;

import org.springframework.cache.Cache;
import com.nexaplatform.dropshipping.infrastructure.messaging.NexaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Plan 300k — Fase 4: este listener consume {@code cache.invalidation} de
 * Kafka y purga la entrada local del caché (Caffeine L1) en cada réplica.
 * En multi-instancia es la forma de propagar invalidaciones sin requerir
 * Redis pub/sub explícito — Kafka ya está en el bus.
 * <p>
 * El productor de estos eventos suele ser el propio backend tras un upsert
 * de producto / categoría / pricing rule, o un sistema externo (admin batch,
 * crawler).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationListener {

    private final CacheManager cacheManager;

    @KafkaListener(topics = NexaTopics.CACHE_INVALIDATION, groupId = "nexadrop-cache-invalidation", containerFactory = "kafkaListenerContainerFactory")
    public void onInvalidation(Map<String, Object> event) {
        Object cacheName = event.get("cache");
        Object key = event.get("key");
        Object all = event.get("allEntries");

        if (cacheName == null) {
            log.warn("Cache invalidation event without 'cache' field: {}", event);
            return;
        }
        Cache cache = cacheManager.getCache(cacheName.toString());
        if (cache == null) {
            log.debug("Unknown cache '{}', skipping invalidation", cacheName);
            return;
        }
        if (Boolean.TRUE.equals(all)) {
            cache.clear();
            log.info("Cache {} cleared via invalidation event", cacheName);
        } else if (key != null) {
            cache.evict(key);
            log.debug("Cache {}#{} evicted via invalidation event", cacheName, key);
        }
    }
}
