package com.nexaplatform.dropshipping.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
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
    public static final String CACHE_PRODUCT_LIST = "product-list"; // página de listado por (filtros+page+size+lang+sort)
    public static final String CACHE_CATEGORY_TREE = "category-tree";
    public static final String CACHE_CATEGORIES_FLAT = "categories-flat";
    public static final String CACHE_SUPPLIERS_FLAT = "suppliers";
    public static final String CACHE_PRICING_AMOUNT = "pricing"; // PricedAmount por productId+ccy
    public static final String CACHE_CURRENCY_RATES = "currency-rates";
    public static final String CACHE_PRODUCT_SPECS = "product-specs";
    public static final String CACHE_PRODUCT_ATTRS = "product-attrs";
    public static final String CACHE_SEARCH = "search"; // resultados de /api/search por keyword+lang+page+size (TTL corto)
    // Operador económico de la UE y advertencias por categoría: se leen en CADA ficha y cambian casi nunca.
    public static final String CACHE_EU_COMPLIANCE = "eu-compliance";

    /**
     * Clave de caché que INCLUYE la moneda de display activa (X-Currency) además del método + args.
     * Imprescindible para los listados de productos: el precio mostrado depende de la moneda del usuario,
     * así que sin la moneda en la clave un usuario en EUR vería el precio cacheado del primer usuario
     * (p. ej. USD) y el margen/conversión "no se reflejaría" por moneda. El nombre del método separa el
     * listado genérico del de categoría/proveedor aunque compartan bucket.
     */
    @Bean("currencyAwareKeyGenerator")
    public KeyGenerator currencyAwareKeyGenerator() {
        // Incluye moneda, canal Y PAÍS: el precio depende de los tres (el margen puede variar por país), así
        // que distintos países/canales/monedas NO deben compartir entrada de caché.
        //
        // Y el ROL, que es lo que faltaba: desde que el listado oculta el coste del proveedor y el precio
        // canónico a quien no es admin, la respuesta ya NO es la misma para todos. Sin el rol en la clave,
        // un admin navegando el escaparate dejaba cacheada la página CON esos campos y cualquier usuario
        // —o un anónimo— con los mismos filtros la recibía tal cual durante los cinco minutos de TTL, lo
        // que reabría la fuga por la puerta de atrás. Y al revés: el admin se quedaba sin las columnas de
        // coste si otro había cacheado antes.
        return (target, method, params) -> method.getName() + ':' + CurrencyHolder.get() + ':'
                + PricingChannelHolder.get() + ':' + PricingCountryHolder.get()
                + (SecurityUtils.isAdmin() ? ":admin" : ":user") + ':'
                + Arrays.deepToString(params);
    }

    /**
     * Cache manager por defecto en CUALQUIER perfil mientras no se active el caché distribuido
     * ({@code nexadrop.cache.distributed=false}, el valor por defecto). Antes estaba atado a perfiles
     * concretos (local/dev/default/test) y dejaba a {@code pre} y {@code pro} SIN cache manager → 500
     * "Cannot find cache". Al depender de la propiedad y no del nombre del perfil queda correcto en
     * todos los entornos.
     *
     * <p>Caffeine corre dentro de la JVM, así que cada instancia tiene su propia copia: vale para
     * desarrollo y para una sola instancia, pero la coherencia multi-instancia la da
     * {@link RedisCacheConfig}.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "nexadrop.cache", name = "distributed", havingValue = "false",
            matchIfMissing = true)
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager(CACHE_PRODUCT_DETAIL, CACHE_PRODUCT_SUMMARY,
                CACHE_PRODUCT_LIST, CACHE_CATEGORY_TREE, CACHE_CATEGORIES_FLAT, CACHE_SUPPLIERS_FLAT,
                CACHE_PRICING_AMOUNT, CACHE_CURRENCY_RATES, CACHE_PRODUCT_SPECS, CACHE_PRODUCT_ATTRS,
                CACHE_SEARCH, CACHE_EU_COMPLIANCE);
        mgr.setCaffeine(Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(5, TimeUnit.MINUTES).recordStats()); // expone métricas a Micrometer
        mgr.setAllowNullValues(false);
        return mgr;
    }
}
