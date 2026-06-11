package com.nexaplatform.dropshipping.infrastructure.security.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Plan 300k req/min — Fase 2: bucket de rate limit en Redis, compartido entre
 * todas las instancias. Cuota global consistente vía operaciones CAS en Lettuce.
 * Activable con {@code nexadrop.ratelimit.redis-enabled=true}.
 */
@Configuration
@ConditionalOnProperty(prefix = "nexadrop.ratelimit", name = "redis-enabled", havingValue = "true")
public class DistributedBucketFactory {

    /**
     * Bean Spring `distributedBucketFactory` — su mera existencia desactiva el
     * {@link InMemoryBucketFactory} via {@code @ConditionalOnMissingBean}.
     */
    @Bean("distributedBucketFactory")
    BucketFactory distributedBucketFactory(RedisClient redisClient) {
        StatefulRedisConnection<String, byte[]> connection =
                redisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        ProxyManager<String> proxy = LettuceBasedProxyManager.builderFor(connection)
                .withExpirationStrategy(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                .build();

        return (key, capacity, period) -> {
            BucketConfiguration cfg = BucketConfiguration.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(capacity)
                            .refillIntervally(capacity, period)
                            .build())
                    .build();
            return proxy.builder().build("nx036:rl:" + key, () -> cfg);
        };
    }
}
