package com.nexaplatform.dropshipping.config;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.security.RateLimitFilter;
import org.springframework.cache.CacheManager;
import org.springframework.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

/**
 * Base de los tests de integración de ENDPOINT (Fase 4): arranca el contexto completo con un servidor
 * real en puerto aleatorio + Postgres de Testcontainers, expone un {@link WebTestClient} apuntando al
 * servidor y un {@link JwtTestUtil} para autenticar por rol. Limpia las tablas antes de cada test.
 *
 * <p>Sin core: {@link TestContainersConfiguration} y {@link JwtTestUtil} se importan desde este repo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestContainersConfiguration.class, JwtTestUtil.class})
public abstract class BaseIntegration {

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JwtTestUtil jwt;

    /**
     * Sin broker real en los tests, {@code kafkaTemplate.send(...)} bloquea {@code max.block.ms}
     * (60s) buscando metadata y revienta el arranque. Lo mockeamos: los envíos (fire-and-forget,
     * p.ej. product.ingested) quedan en no-op. Si un test necesita verificar publicación, stubea aquí.
     */
    @MockitoBean
    protected KafkaTemplate<String, Object> kafkaTemplate;

    protected WebTestClient client;

    @Autowired(required = false)
    private CacheManager cacheManager;

    /** Su caché de tasas vive fuera del {@link CacheManager}; ver {@link #vaciarCaches()}. */
    @Autowired(required = false)
    private CurrencyRateService currencyRateService;

    /**
     * El filtro de límite de peticiones, para devolverle la cuota entre pruebas.
     *
     * <p>Los buckets viven en la JVM y el contexto de Spring se comparte entre clases: sin reiniciarlo,
     * una clase que hace muchas peticiones agota la cuota de la IP y la SIGUIENTE recibe 429 sin que nada
     * esté mal en lo que prueba. No se apaga el límite: hay pruebas que verifican justamente que corta al
     * pasar del tope, y apagarlo las dejaría sin objeto.
     */
    @Autowired(required = false)
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUpClientAndCleanDb() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        cleanAllTables();
        vaciarCaches();
        if (rateLimitFilter != null) {
            rateLimitFilter.reset();
        }
    }

    /**
     * Vacía las cachés de aplicación además de las tablas.
     *
     * <p>Vaciar solo la base NO deja el sistema limpio: Caffeine vive en la JVM y el contexto de Spring se
     * comparte entre clases de prueba, así que lo que una dejó cacheado —listados, fichas, precios,
     * resultados de búsqueda— sobrevive al TRUNCATE y la siguiente lo lee como si fuera suyo. Un test cuyo
     * resultado depende de quién se ejecutó antes no certifica nada.
     *
     * <p>Y no basta con las del {@link CacheManager}: {@link CurrencyRateService} lleva su PROPIA caché en
     * memoria, con una ventana de cinco minutos que ningún vaciado alcanzaba. Como {@link #cleanAllTables()}
     * borra también {@code currency_rate}, la caché y la base quedaban descolgadas: durante los primeros
     * cinco minutos de vida de la JVM el proceso seguía sirviendo las tasas que sembró la migración —así
     * que una clase sola pasaba— y, al vencer la ventana dentro de una clase que no repone divisas, la
     * recarga dejaba la caché VACÍA y con sello nuevo otros cinco minutos. A partir de ahí, toda lectura
     * del catálogo con precio en yuanes respondía 404 «Unknown source currency: CNY»: eran los 12 casos de
     * búsqueda y los 5 de la API de partners que solo fallaban con la suite entera. Invalidándola aquí, la
     * siguiente lectura la reconstruye con las divisas que haya sembrado el test que está corriendo.
     */
    protected void vaciarCaches() {
        if (currencyRateService != null) {
            currencyRateService.invalidateCache();
        }
        if (cacheManager == null) {
            return;
        }
        for (String nombre : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(nombre);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /** Cuántas veces se reintenta el vaciado cuando Postgres corta un interbloqueo. */
    private static final int INTENTOS_DE_VACIADO = 3;

    /** TRUNCATE de todas las tablas de negocio (deja fuera las de Liquibase). */
    protected void cleanAllTables() {
        // jwk_keys se excluye: la clave RSA activa se genera al arrancar el contexto (una sola vez);
        // si se truncara, JwtTestUtil no podría firmar tokens en los tests siguientes.
        //
        // El ORDER BY NO es cosmético. Sin él, `pg_tables` devuelve las filas en el orden en que están
        // en el catálogo, que cambia entre ejecuciones; y como `TRUNCATE a, b, c` toma un bloqueo
        // exclusivo por tabla EN EL ORDEN EN QUE SE LISTAN, cada pasada pedía los bloqueos en un orden
        // distinto. Eso es exactamente lo que se necesita para un interbloqueo por orden de bloqueo.
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename NOT LIKE 'databasechange%' AND tablename <> 'jwk_keys' "
                        + "ORDER BY tablename",
                String.class);
        if (tables.isEmpty()) {
            return;
        }
        String joined = String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList());
        vaciaReintentandoElInterbloqueo("TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE");
    }

    /**
     * Vacía reintentando si Postgres corta un interbloqueo.
     *
     * <p>Un orden estable quita la MITAD del problema: la que dependía de nosotros. La otra mitad no se
     * puede ordenar, porque la otra parte del ciclo es el trabajo de fondo de la aplicación —los
     * consumidores de Kafka, el vaciado de la bandeja de salida, los planificadores— que sigue vivo
     * mientras se limpia y toma sus bloqueos en el orden que le dicta el negocio, no el alfabético.
     *
     * <p>Lo que se rompía: UNA prueba al azar de las 996 moría por pasada, y nunca la misma. Un fallo
     * así no se lee como un defecto sino como «la batería es inestable», y una batería que siempre está
     * en rojo por un motivo que nadie mira deja de ser una puerta. El interbloqueo, además, lo detecta
     * Postgres y mata a UNA de las dos partes: cuando nos toca, la otra transacción ya ha terminado, así
     * que reintentar acto seguido basta.
     *
     * <p>Sin esperas por reloj a propósito —la norma del repositorio las prohíbe y aquí no hacen falta:
     * el detector de interbloqueos ya ha esperado su propio plazo antes de contestar.
     */
    private void vaciaReintentandoElInterbloqueo(String sentencia) {
        ConcurrencyFailureException ultimo = null;
        for (int intento = 1; intento <= INTENTOS_DE_VACIADO; intento++) {
            try {
                jdbcTemplate.execute(sentencia);
                return;
            } catch (ConcurrencyFailureException e) {
                ultimo = e;
            }
        }
        throw ultimo;
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
