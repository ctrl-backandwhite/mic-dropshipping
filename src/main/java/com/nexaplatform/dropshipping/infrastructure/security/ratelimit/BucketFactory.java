package com.nexaplatform.dropshipping.infrastructure.security.ratelimit;

import io.github.bucket4j.Bucket;

import java.time.Duration;

/**
 * Estrategia de creación de buckets de rate limit. La implementación por
 * defecto guarda buckets en memoria del proceso. En prod multi-instancia se
 * inyecta {@link DistributedBucketFactory} (Bucket4j sobre Redis) para que
 * todas las réplicas compartan el mismo bucket — cuota global, no per-instance.
 */
public interface BucketFactory {
    Bucket resolve(String key, long capacity, Duration period);
}
