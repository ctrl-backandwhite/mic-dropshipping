package com.nexaplatform.dropshipping.infrastructure.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORIES_FLAT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CATEGORY_TREE;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_CURRENCY_RATES;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_SEARCH;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_SUPPLIERS_FLAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Caché distribuida en Redis (solo con {@code nexadrop.cache.distributed=true}).
 *
 * <p>Lo que se fija aquí son los plazos de caducidad por caché: en dropshipping el precio y el stock
 * cambian a menudo, así que un TTL largo en el listado se traduce en enseñar precios viejos. Y el
 * prefijo de clave aísla esta aplicación de cualquier otra que comparta el mismo Redis: sin él, dos
 * despliegues se pisarían las entradas.
 */
class Cov04RedisCacheConfigTest {

    private RedisCacheManager manager;

    @BeforeEach
    void setUp() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        manager = (RedisCacheManager) new RedisCacheConfig().redisCacheManager(factory);
        // Los cachés iniciales se materializan al inicializar el manager (en la app lo hace el contenedor).
        manager.afterPropertiesSet();
    }

    /** Configuración efectiva de una caché, desenvolviendo el decorador transaccional. */
    private RedisCacheConfiguration config(String cacheName) {
        Cache cache = manager.getCache(cacheName);
        assertThat(cache).as("caché %s", cacheName).isNotNull();
        Cache target = cache instanceof TransactionAwareCacheDecorator decorator
                ? decorator.getTargetCache() : cache;
        return ((RedisCache) target).getCacheConfiguration();
    }

    private Duration ttl(String cacheName) {
        return config(cacheName).getTtlFunction().getTimeToLive("clave", "valor");
    }

    @Test
    void losDatosQueCambianConElPrecioCaducanEnUnMinuto() {
        // Listado y búsqueda llevan precio y stock: más de un minuto de retraso ya es enseñar otra cosa.
        assertThat(ttl(CACHE_PRODUCT_LIST)).isEqualTo(Duration.ofSeconds(60));
        assertThat(ttl(CACHE_SEARCH)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void laFichaYElResumenDeProductoAguantanMasQueElListado() {
        assertThat(ttl(CACHE_PRODUCT_DETAIL)).isEqualTo(Duration.ofMinutes(5));
        assertThat(ttl(CACHE_PRODUCT_SUMMARY)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void loQueCasiNuncaCambiaSeGuardaMuchoMasTiempo() {
        assertThat(ttl(CACHE_CATEGORY_TREE)).isEqualTo(Duration.ofMinutes(15));
        assertThat(ttl(CACHE_CATEGORIES_FLAT)).isEqualTo(Duration.ofMinutes(15));
        assertThat(ttl(CACHE_SUPPLIERS_FLAT)).isEqualTo(Duration.ofMinutes(30));
        assertThat(ttl(CACHE_CURRENCY_RATES)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void unaCacheNoDeclaradaHeredaElPlazoPorDefectoYNoSeQuedaEterna() {
        // Sin TTL por defecto, una caché nueva guardaría datos para siempre en Redis.
        manager.getCache("cache-nueva");

        assertThat(ttl("cache-nueva")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void lasClavesVanPrefijadasParaNoPisarOtraAplicacionDelMismoRedis() {
        assertThat(config(CACHE_PRODUCT_LIST).getKeyPrefixFor(CACHE_PRODUCT_LIST))
                .isEqualTo("nx036:cache:product-list:");
    }

    @Test
    void nuncaSeCacheaUnNulo() {
        // Un null cacheado deja el producto invisible en el escaparate hasta que caduque la entrada.
        assertThat(config(CACHE_PRODUCT_DETAIL).getAllowCacheNullValues()).isFalse();
    }

    @Test
    void loQueSeCacheaDentroDeUnaTransaccionSoloSeEscribeSiLaTransaccionConfirma() {
        // Sin esto, una escritura que luego se deshace dejaría el dato revertido cacheado para todos.
        assertThat(manager.getCache(CACHE_PRODUCT_DETAIL))
                .isInstanceOf(TransactionAwareCacheDecorator.class);
    }

    @Test
    void estanDeclaradasTodasLasCachesQueUsaLaAplicacion() {
        assertThat(manager.getCacheNames()).contains(CACHE_PRODUCT_DETAIL, CACHE_PRODUCT_SUMMARY,
                CACHE_PRODUCT_LIST, CACHE_CATEGORY_TREE, CACHE_CATEGORIES_FLAT, CACHE_SUPPLIERS_FLAT,
                CACHE_CURRENCY_RATES, CACHE_SEARCH);
    }
}
