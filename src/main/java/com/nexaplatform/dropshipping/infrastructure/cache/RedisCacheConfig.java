package com.nexaplatform.dropshipping.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.*;

/**
 * Cache distribuido en Redis. Activo en perfil {@code prod} / {@code cluster}
 * (cuando hay más de una instancia detrás del LB y la coherencia de cache
 * tiene que estar fuera del proceso).
 * <p>
 * El TTL es agresivo por defecto (60 s para listings, 5 min para PDP) porque
 * en dropshipping los datos cambian rápido pero son razonables consistir
 * eventualmente — el CDN delante absorbe el grueso del tráfico, así que el
 * Redis solo ve el "long tail" de cache misses.
 */
@Configuration
@Profile({"prod", "cluster"})
@ConditionalOnProperty(prefix = "nexadrop.cache", name = "distributed", havingValue = "true", matchIfMissing = true)
public class RedisCacheConfig {

    @Bean
    @Primary
    CacheManager redisCacheManager(RedisConnectionFactory cf) {
        // Default 5 min TTL; overrides por cache abajo.
        RedisCacheConfiguration defaults = baseConfig(Duration.ofMinutes(5));

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                CACHE_PRODUCT_DETAIL,   baseConfig(Duration.ofMinutes(5)),
                CACHE_PRODUCT_SUMMARY,  baseConfig(Duration.ofMinutes(2)),
                CACHE_CATEGORY_TREE,    baseConfig(Duration.ofMinutes(15)),
                CACHE_CATEGORIES_FLAT,  baseConfig(Duration.ofMinutes(15)),
                CACHE_SUPPLIERS_FLAT,   baseConfig(Duration.ofMinutes(30)),
                CACHE_PRICING_AMOUNT,   baseConfig(Duration.ofMinutes(5)),
                CACHE_CURRENCY_RATES,   baseConfig(Duration.ofMinutes(10)),
                CACHE_PRODUCT_SPECS,    baseConfig(Duration.ofMinutes(15)),
                CACHE_PRODUCT_ATTRS,    baseConfig(Duration.ofMinutes(15))
        );

        return RedisCacheManager.builder(cf)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .transactionAware()
                .build();
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        ObjectMapper mapper = redisObjectMapper();
        // Jackson2JsonRedisSerializer<Object> en su forma no-deprecada (con type-info en el mapper).
        Jackson2JsonRedisSerializer<Object> valueSerializer = new Jackson2JsonRedisSerializer<>(mapper, Object.class);
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .computePrefixWith(name -> "nx036:cache:" + name + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));
    }

    /** ObjectMapper preparado para preservar tipos al deserializar JSON desde Redis. */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // Default-typing controlado: imprescindible para deserializar polimórficos
        // sin abrir el vector de gadget chains.
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }
}
