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
 * Cache distribuido en Redis. Se activa SOLO cuando {@code nexadrop.cache.distributed=true}
 * (env {@code CACHE_DISTRIBUTED=true}), independiente del nombre del perfil — así prod lo enciende
 * sin acoplar el cache al nombre del entorno. Si está en false (default), se usa el Caffeine L1 de
 * {@link CacheConfig}. Pensado para cuando hay más de una instancia detrás del LB y la coherencia de
 * cache tiene que estar fuera del proceso.
 * <p>
 * El TTL es agresivo por defecto (60 s para listings, 5 min para PDP) porque
 * en dropshipping los datos cambian rápido pero son razonables consistir
 * eventualmente — el CDN delante absorbe el grueso del tráfico, así que el
 * Redis solo ve el "long tail" de cache misses.
 */
@Configuration
@ConditionalOnProperty(prefix = "nexadrop.cache", name = "distributed", havingValue = "true")
public class RedisCacheConfig {

    @Bean
    @Primary
    CacheManager redisCacheManager(RedisConnectionFactory cf) {
        // Default 5 min TTL; overrides por cache abajo.
        RedisCacheConfiguration defaults = baseConfig(Duration.ofMinutes(5));

        // product-list con TTL corto (60 s): tolera menos staleness que el árbol de categorías por el
        // precio/stock. Se invalida además al mutar productos. Map.ofEntries (más de 10 entradas).
        Map<String, RedisCacheConfiguration> perCache = Map.ofEntries(
                Map.entry(CACHE_PRODUCT_DETAIL, baseConfig(Duration.ofMinutes(5))),
                Map.entry(CACHE_PRODUCT_SUMMARY, baseConfig(Duration.ofMinutes(2))),
                Map.entry(CACHE_PRODUCT_LIST, baseConfig(Duration.ofSeconds(60))),
                Map.entry(CACHE_CATEGORY_TREE, baseConfig(Duration.ofMinutes(15))),
                Map.entry(CACHE_CATEGORIES_FLAT, baseConfig(Duration.ofMinutes(15))),
                Map.entry(CACHE_SUPPLIERS_FLAT, baseConfig(Duration.ofMinutes(30))),
                Map.entry(CACHE_PRICING_AMOUNT, baseConfig(Duration.ofMinutes(5))),
                Map.entry(CACHE_CURRENCY_RATES, baseConfig(Duration.ofMinutes(10))),
                Map.entry(CACHE_PRODUCT_SPECS, baseConfig(Duration.ofMinutes(15))),
                Map.entry(CACHE_PRODUCT_ATTRS, baseConfig(Duration.ofMinutes(15))));

        return RedisCacheManager.builder(cf).cacheDefaults(defaults).withInitialCacheConfigurations(perCache)
                .transactionAware().build();
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        ObjectMapper mapper = redisObjectMapper();
        // Jackson2JsonRedisSerializer<Object> en su forma no-deprecada (con type-info en el mapper).
        Jackson2JsonRedisSerializer<Object> valueSerializer = new Jackson2JsonRedisSerializer<>(mapper, Object.class);
        return RedisCacheConfiguration.defaultCacheConfig().entryTtl(ttl).disableCachingNullValues()
                .computePrefixWith(name -> "nx036:cache:" + name + ":")
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));
    }

    /** ObjectMapper preparado para preservar tipos al deserializar JSON desde Redis. */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // Default-typing controlado: imprescindible para deserializar polimórficos
        // sin abrir el vector de gadget chains.
        mapper.activateDefaultTyping(BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        return mapper;
    }
}
