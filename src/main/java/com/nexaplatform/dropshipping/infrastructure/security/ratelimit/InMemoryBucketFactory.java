package com.nexaplatform.dropshipping.infrastructure.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Implementación in-memory; sólo aplica si no se ha cargado la variante Redis. */
@Component
@ConditionalOnMissingBean(name = "distributedBucketFactory")
public class InMemoryBucketFactory implements BucketFactory {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Bucket resolve(String key, long capacity, Duration period) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, period)
                        .build())
                .build());
    }
}
