package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.StorefrontCatalogApi;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.api.mapper.ProductListFilters;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.List;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;

/**
 * Mantiene calientes las respuestas que abre TODO el mundo al entrar.
 *
 * <h2>El problema que resuelve</h2>
 *
 * <p>La caché funciona: la portada tarda 3-6 segundos la primera vez y 0,03 la segunda. El problema
 * nunca fue la segunda — es que casi nadie llegaba a ser la segunda. Tres cosas conspiraban:
 *
 * <ul>
 *   <li>La caché es <b>por réplica</b> (Caffeine vive dentro de la JVM), así que cada réplica tiene
 *       que calentarse por su cuenta y un visitante puede caer en la fría.</li>
 *   <li>Caduca a los <b>5 minutos</b>, de modo que cada cinco minutos alguien vuelve a pagar la
 *       primera vez.</li>
 *   <li>La clave incluye idioma, divisa, canal, país y rol, así que hay muchas entradas distintas y
 *       cada una se calienta sola.</li>
 * </ul>
 *
 * <p>Sumado: quien entra a una hora tranquila paga los segundos casi siempre. Es justo lo que se ve al
 * abrir el escaparate y quedarse mirando los recuadros grises.
 *
 * <h2>Cómo lo resuelve</h2>
 *
 * <p>Cada réplica se calienta a sí misma, antes de que caduque, con las combinaciones que de verdad
 * usa la gente. Lo paga una tarea de fondo a la que no espera nadie, en vez del primer visitante.
 *
 * <p>El intervalo va por debajo del tiempo de vida de la caché a propósito: si fuera igual o mayor
 * habría siempre una ventana con la entrada ya caducada y todavía sin rehacer, que es exactamente el
 * hueco que se quiere cerrar.
 *
 * <p>Se calienta la entrada <b>pública</b>, no la de administrador: la clave incluye el rol y esta
 * tarea no se autentica. Es lo correcto — el escaparate lo abren visitantes, y el panel lo abren unas
 * pocas personas que además navegan seguido y se calientan la suya solas.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrecalentadorDeCatalogo {

    private static final ProductListFilters SIN_FILTROS =
            new ProductListFilters(null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);

    private final StorefrontCatalogApi catalogo;
    private final CatalogStorefrontReadService lectura;
    private final CacheManager cacheManager;
    private final KeyGenerator currencyAwareKeyGenerator;

    @Value("${nexadrop.catalogo.precalentar:true}")
    private boolean activo;

    /**
     * Combinaciones de idioma y divisa que se mantienen calientes.
     *
     * <p>No son los ocho idiomas por las nueve divisas: eso serían setenta y dos entradas rehechas
     * cada pocos minutos para que la inmensa mayoría no las mirara nadie. Son las que concentran las
     * visitas; el resto sigue calentándose sola con el primer visitante, como hasta ahora.
     */
    @Value("${nexadrop.catalogo.precalentar-combinaciones:es:EUR,en:USD,es:USD,en:EUR}")
    private List<String> combinaciones;

    @Value("${nexadrop.catalogo.precalentar-secciones:6}")
    private int porSeccion;

    /**
     * Cada cuatro minutos, por debajo de los cinco de vida de la caché.
     *
     * <p>{@code fixedDelay} y no {@code fixedRate}: cuenta desde que TERMINA la pasada anterior, así
     * que si una tarda más de lo normal no se solapa consigo misma.
     *
     * <p>La primera pasada espera CINCO MINUTOS, y no veinte segundos como al principio. El motivo:
     * la aplicación tarda entre 200 y 367 segundos en arrancar —Liquibase migra antes de servir—, y
     * ponerse a calentar mientras todavía está levantándose le añade trabajo justo cuando menos
     * margen tiene. El 5-sep-2026 una réplica de PRE se quedó dando vueltas por eso: la sonda de
     * vida la mataba antes de que terminara.
     *
     * <p>Nadie pierde nada esperando: durante esos cinco minutos la caché se llena sola con las
     * primeras visitas, que es como funcionaba antes de que existiera esta tarea.
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = 240_000)
    public void precalienta() {
        if (!activo) {
            return;
        }
        long inicio = System.nanoTime();
        int hechas = 0;
        for (String combinacion : combinaciones) {
            String[] partes = combinacion.split(":");
            if (partes.length != 2) {
                log.warn("::> [PRECALENTADO] Combinación mal escrita, se ignora: '{}' (se espera idioma:DIVISA)",
                        combinacion);
                continue;
            }
            if (calienta(partes[0].trim(), partes[1].trim())) {
                hechas++;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("::> [PRECALENTADO] {} de {} combinaciones en {} ms", hechas, combinaciones.size(),
                    (System.nanoTime() - inicio) / 1_000_000);
        }
    }

    private boolean calienta(String idioma, String divisa) {
        try {
            // Los mismos valores que pondría el filtro de una petición real: si no coinciden, se
            // calentaría una entrada que luego nadie consulta.
            CurrencyHolder.set(divisa);
            PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
            PricingCountryHolder.clear();

            // Se TIRA la entrada antes de pedirla. Sin esto el precalentado no calienta nada pasada la
            // primera vez: `@Cacheable` devuelve lo guardado sin reescribirlo, así que la caducidad
            // sigue contando desde la primera escritura. Medido en PRE el 5-sep-2026: el primer ciclo
            // tardó 18 segundos (calentó) y el segundo 2 milisegundos (acierto de caché, no calentó),
            // y la entrada caducaba a los 5 minutos dejando TRES minutos fríos de cada ocho — justo el
            // hueco que esto viene a cerrar.
            //
            // El desalojo abre una ventana de unos segundos hasta que se rehace. No es un problema:
            // Caffeine bloquea por clave, así que a lo sumo un visitante espera lo que habría esperado
            // igualmente, y solo durante esos segundos en vez de durante tres minutos.
            olvida(PORTADA, idioma, porSeccion);
            catalogo.homeSections(idioma, porSeccion);
            catalogo.categoriesFlat(idioma);
            lectura.categoriesTree(idioma);
            lectura.productListFull(0, 24, idioma, SIN_FILTROS, null);
            return true;
        } catch (RuntimeException e) {
            // Que falle el precalentado NO puede tumbar nada: es una comodidad, no un requisito. Si la
            // base de datos está ocupada o el buscador no responde, se avisa y se reintenta en la
            // siguiente vuelta; el visitante sigue teniendo el camino de siempre.
            log.warn("::> [PRECALENTADO] No se pudo calentar {}/{}: {}", idioma, divisa, e.toString());
            return false;
        } finally {
            CurrencyHolder.clear();
            PricingChannelHolder.clear();
            PricingCountryHolder.clear();
        }
    }

    /** El método de la portada, para poder pedirle su clave al MISMO generador que usa la caché. */
    private static final Method PORTADA = metodoDePortada();

    private static Method metodoDePortada() {
        try {
            return StorefrontCatalogApi.class.getMethod("homeSections", String.class, int.class);
        } catch (NoSuchMethodException e) {
            // Si alguien cambia la firma, que se vea al arrancar y no como un precalentado que en
            // silencio deja de calentar.
            throw new IllegalStateException("No se encuentra homeSections: revisa StorefrontCatalogApi", e);
        }
    }

    /**
     * Tira la entrada de caché de esa llamada, para que la siguiente la vuelva a calcular Y a escribir.
     *
     * <p>La clave la pide al MISMO generador que usa {@code @Cacheable} ({@code currencyAwareKeyGenerator}),
     * no se compone a mano: si se escribiera aquí una copia de su formato, cualquier cambio en el
     * generador dejaría el precalentado desalojando una clave que no existe, sin que nada fallara.
     */
    private void olvida(Method metodo, Object... argumentos) {
        Cache cache = cacheManager.getCache(CACHE_PRODUCT_LIST);
        if (cache == null) {
            return;
        }
        cache.evict(currencyAwareKeyGenerator.generate(catalogo, metodo, argumentos));
    }
}
