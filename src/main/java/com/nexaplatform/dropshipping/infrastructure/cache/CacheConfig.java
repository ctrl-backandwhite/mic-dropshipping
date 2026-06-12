package com.nexaplatform.dropshipping.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.TimeUnit;

/**
 * Configuración de caché aplicativa (Spring Cache).
 * <p>
 * En todos los perfiles arrancamos con Caffeine in-process (L1) — sub-µs,
 * no requiere red. En el perfil {@code prod} (o {@code cluster}) este L1 se
 * coloca delante de un L2 distribuido en Redis (ver
 * {@link RedisCacheConfig}). El TTL es por cache para reflejar la frescura
 * que cada dato tolera.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    // Nombres centralizados para que los @Cacheable apunten al mismo bucket
    // que su contrato de evicción.
    public static final String CACHE_PRODUCT_DETAIL = "pdp"; // PDP completo por slug+lang+ccy
    public static final String CACHE_PRODUCT_SUMMARY = "summary"; // ProductSummaryView por id+lang
    public static final String CACHE_CATEGORY_TREE = "category-tree";
    public static final String CACHE_CATEGORIES_FLAT = "categories-flat";
    public static final String CACHE_SUPPLIERS_FLAT = "suppliers";
    public static final String CACHE_PRICING_AMOUNT = "pricing"; // PricedAmount por productId+ccy
    public static final String CACHE_CURRENCY_RATES = "currency-rates";
    public static final String CACHE_PRODUCT_SPECS = "product-specs";
    public static final String CACHE_PRODUCT_ATTRS = "product-attrs";

    /**
     * Local-only fallback que se usa SI no hay Redis configurado (perfil dev /
     * test sin docker). Caffeine corre dentro de la JVM, así que cada instancia
     * tiene su propia copia — válido para desarrollo local pero NO para prod
     * multi-instancia (esa coherencia la da {@link RedisCacheConfig}).
     */
    @Bean
    @Primary
    @Profile({"local", "dev", "default", "test"})
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(CACHE_PRODUCT_DETAIL, CACHE_PRODUCT_SUMMARY,
                CACHE_CATEGORY_TREE, CACHE_CATEGORIES_FLAT, CACHE_SUPPLIERS_FLAT, CACHE_PRICING_AMOUNT,
                CACHE_CURRENCY_RATES, CACHE_PRODUCT_SPECS, CACHE_PRODUCT_ATTRS);
        mgr.setCaffeine(Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(5, TimeUnit.MINUTES).recordStats()); // expone métricas a Micrometer
        mgr.setAllowNullValues(false);
        return mgr;
    }
}
