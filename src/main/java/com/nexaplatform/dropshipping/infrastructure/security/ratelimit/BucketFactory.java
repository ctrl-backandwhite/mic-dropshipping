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

    /**
     * Vacía los cubos que guarde la implementación. Solo tiene efecto en la variante en memoria: la
     * distribuida vive en Redis con caducidad propia y no necesita limpieza.
     *
     * <p>Existe para que el gancho de pruebas {@code RateLimitFilter.reset()} pueda vaciar el estado que
     * el filtro usa DE VERDAD. Sin esto, el gancho limpiaba un mapa que en la aplicación real nunca se
     * rellena y la cuota consumida por una prueba se arrastraba a la siguiente.
     */
    default void clear() {
        // Por defecto no hay estado propio que limpiar.
    }
}
