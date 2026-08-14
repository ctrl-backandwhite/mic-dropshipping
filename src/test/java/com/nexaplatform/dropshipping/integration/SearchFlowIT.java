package com.nexaplatform.dropshipping.integration;

import tools.jackson.databind.JsonNode;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Búsqueda del escaparate de punta a punta: los 8 idiomas, acentos, chino, plurales y erratas — y, sobre
 * todo, que NO devuelva lo que no se ha pedido.
 *
 * <p><b>Qué camino se está probando.</b> El motor principal es OpenSearch, pero el perfil de test lo apunta a
 * un puerto CERRADO ({@code 127.0.0.1:1}), de modo que el buscador nunca responde. Ese es justamente el
 * escenario que interesa blindar: cuando el buscador no responde, {@code ProductSearchService} devuelve
 * {@code Optional.empty()} y el catálogo cae al <b>fallback SQL</b> ({@code searchStorefront} con
 * {@code nx_norm}/{@code nx_wmatch}). Estos tests recorren ese fallback, que es el que tiene que sostener la
 * tienda cuando el índice se cae o aún no está construido — exactamente el fallo que dejó dos entornos sin
 * arrancar con la suite en verde.
 *
 * <p>El puerto cerrado no es un detalle de fontanería: mientras el perfil apuntaba a {@code localhost:9200},
 * la suite consultaba el buscador REAL de la máquina de quien la ejecutase. Ese índice nunca contiene los
 * productos que aquí se siembran por {@code jdbcTemplate}, así que TODA búsqueda devolvía cero y estos
 * mismos tests pasaban en CI (sin buscador) y fallaban en local (con buscador levantado). Ver el comentario
 * de {@code nexadrop.opensearch.uris} en {@code application-test.yml}.
 *
 * <p><b>Precisión: por qué las erratas y los plurales sí, y los vecinos no.</b> El match difuso usa el
 * operador de similitud ESTRICTA de palabra de {@code pg_trgm}. Los valores medidos sobre estos mismos datos
 * son: {@code botas}↔{@code bota militar} = 0,57; {@code zapatoss}↔{@code zapatos deportivos} = 0,70;
 * {@code botas}↔{@code botones dorados} = 0,27. El umbral configurado es 0,45 (y 0,50 el de PostgreSQL por
 * defecto, si el {@code ALTER DATABASE} de la migración no llegó a aplicarse a la conexión): las
 * aserciones de este test se cumplen con cualquiera de los dos, a propósito.
 *
 * <p>Ver {@code CatalogFlowIT} para el detalle del sembrado y de las cachés; aquí aplica lo mismo.
 */
class SearchFlowIT extends BaseIntegration {

    private static final String PRODUCTS = "/api/catalog/products";
    private static final String SEARCH = "/api/search";
    private static final String AUTH = "Authorization";
    private static final String IMG_CDN = "https://cdn.nx036.test/img/";

    private static final String SLUG_ABRIGO = "abrigo-multilingue";
    private static final String SLUG_CAMION = "camion-electrico";
    private static final String SLUG_BOTAS = "botas-de-agua";
    private static final String SLUG_BOTA_MILITAR = "bota-militar";
    private static final String SLUG_ZAPATOS = "zapatos-deportivos";
    private static final String SLUG_BOTONES = "botones-dorados";
    private static final String SLUG_FALDA = "falda-plisada";

    /** Título en cada uno de los 8 idiomas del catálogo, para el producto multilingüe. */
    private static final Map<String, String> TITULOS_ABRIGO = new LinkedHashMap<>();
    /** Palabra con la que se busca en cada idioma (una distinta por idioma, sin solapes). */
    private static final Map<String, String> TERMINOS_ABRIGO = new LinkedHashMap<>();

    static {
        TITULOS_ABRIGO.put("es", "Abrigo de invierno acolchado");
        TITULOS_ABRIGO.put("en", "Padded winter coat");
        TITULOS_ABRIGO.put("pt", "Casaco de inverno acolchoado");
        TITULOS_ABRIGO.put("fr", "Manteau matelasse pour hiver");
        TITULOS_ABRIGO.put("it", "Cappotto invernale imbottito");
        TITULOS_ABRIGO.put("de", "Gefuetterter Wintermantel");
        TITULOS_ABRIGO.put("nl", "Gewatteerde winterjas");
        TITULOS_ABRIGO.put("zh", "冬季加厚外套");

        TERMINOS_ABRIGO.put("es", "abrigo");
        TERMINOS_ABRIGO.put("en", "coat");
        TERMINOS_ABRIGO.put("pt", "casaco");
        TERMINOS_ABRIGO.put("fr", "manteau");
        TERMINOS_ABRIGO.put("it", "cappotto");
        TERMINOS_ABRIGO.put("de", "wintermantel");
        TERMINOS_ABRIGO.put("nl", "winterjas");
        TERMINOS_ABRIGO.put("zh", "外套");
    }

    @Autowired
    private CacheManager cacheManager;

    private String userToken;
    private UUID categoria;
    private UUID proveedor;

    @BeforeEach
    void seedCatalogoBuscable() {
        clearAllCaches();
        seedCurrencies();
        UUID userId = insertUser("busqueda@nx036.test");
        userToken = jwt.userToken(userId, "busqueda@nx036.test", "USER");
        proveedor = insertSupplier();
        categoria = insertCategory();

        insertProduct(SLUG_ABRIGO, "冬季加厚外套", TITULOS_ABRIGO);
        insertProduct(SLUG_CAMION, "电动玩具卡车", Map.of("es", "Camión eléctrico de juguete", "en", "Electric toy truck"));
        insertProduct(SLUG_BOTAS, "雨靴", Map.of("es", "Botas de agua para lluvia", "en", "Rain boots"));
        insertProduct(SLUG_BOTA_MILITAR, "军靴", Map.of("es", "Bota militar de cuero", "en", "Leather military boot"));
        insertProduct(SLUG_ZAPATOS, "运动鞋", Map.of("es", "Zapatos deportivos rojos", "en", "Red running shoes"));
        // Vecino trigramático de "botas" que NO debe salir al buscar botas (similitud medida: 0,27).
        insertProduct(SLUG_BOTONES, "金色纽扣", Map.of("es", "Botones dorados", "en", "Golden buttons"));
        // Falso positivo clásico: la prenda no es unas botas, solo las menciona en su DESCRIPCIÓN.
        UUID falda = insertProduct(SLUG_FALDA, "百褶裙", Map.of("es", "Falda plisada", "en", "Pleated skirt"));
        jdbcTemplate.update("UPDATE product_translation SET description = ? WHERE product_id = ? AND language = 'es'",
                "Falda plisada que combina de maravilla con botas altas", falda);
    }

    /* ============================================================================================
     * LOS 8 IDIOMAS
     * ========================================================================================== */

    @Test
    @DisplayName("Busca el mismo producto en los 8 idiomas, cada uno con su palabra")
    void busquedaEnLosOchoIdiomas() {
        for (Map.Entry<String, String> entrada : TERMINOS_ABRIGO.entrySet()) {
            String idioma = entrada.getKey();
            String termino = entrada.getValue();

            List<String> encontrados = buscar(termino, idioma);

            assertThat(encontrados).as("buscando '%s' en '%s'", termino, idioma).contains(SLUG_ABRIGO);
        }
    }

    @Test
    @DisplayName("El título se devuelve en el idioma pedido")
    void tituloEnElIdiomaPedido() {
        JsonNode page = getJson(PRODUCTS + "?size=50&lang=fr&q=manteau", userToken);

        assertThat(page.get("items").get(0).get("title").asText()).isEqualTo(TITULOS_ABRIGO.get("fr"));
    }

    /* ============================================================================================
     * ACENTOS, CHINO, PLURALES Y ERRATAS
     * ========================================================================================== */

    @Test
    @DisplayName("Acentos: 'camion' encuentra 'Camión', y 'CAMIÓN' en mayúsculas también")
    void acentosYMayusculas() {
        assertThat(buscar("camion", "es")).contains(SLUG_CAMION);
        assertThat(buscar("CAMIÓN", "es")).contains(SLUG_CAMION);
        assertThat(buscar("eléctrico", "es")).contains(SLUG_CAMION);
    }

    @Test
    @DisplayName("Chino: se busca por los caracteres del título original")
    void busquedaEnChino() {
        assertThat(buscar("外套", "zh")).contains(SLUG_ABRIGO);
        assertThat(buscar("雨靴", "es")).as("el chino casa aunque el idioma activo sea otro").contains(SLUG_BOTAS);
    }

    @Test
    @DisplayName("Plurales: 'bota' encuentra las botas, y 'botas' encuentra la bota militar")
    void plurales() {
        assertThat(buscar("bota", "es")).contains(SLUG_BOTAS, SLUG_BOTA_MILITAR);
        assertThat(buscar("botas", "es")).contains(SLUG_BOTAS, SLUG_BOTA_MILITAR);
    }

    @Test
    @DisplayName("Erratas: 'zapatoss' sigue encontrando los zapatos deportivos")
    void erratas() {
        assertThat(buscar("zapatoss", "es")).contains(SLUG_ZAPATOS);
    }

    /* ============================================================================================
     * SIN FALSOS POSITIVOS
     * ========================================================================================== */

    @Test
    @DisplayName("Buscar 'botas' NO devuelve 'Botones dorados' ni la falda que solo los menciona")
    void sinFalsosPositivosEvidentes() {
        List<String> resultados = buscar("botas", "es");

        assertThat(resultados).contains(SLUG_BOTAS, SLUG_BOTA_MILITAR);
        assertThat(resultados).as("un vecino trigramático no es un resultado").doesNotContain(SLUG_BOTONES);
        assertThat(resultados).as("la descripción solo se mira si NADA casa por título").doesNotContain(SLUG_FALDA);
    }

    @Test
    @DisplayName("Buscar en un idioma no arrastra parecidos de otro idioma")
    void sinFalsosPositivosEntreIdiomas() {
        // 'coat' (en) no debe traer 'cappotto' (it) ni 'casaco' (pt) por parecido: solo casa el producto que
        // de verdad lleva la palabra, que resulta ser el mismo en todos los idiomas.
        List<String> resultados = buscar("coat", "en");

        assertThat(resultados).containsExactly(SLUG_ABRIGO);
    }

    /* ============================================================================================
     * CASOS BORDE DEL TÉRMINO DE BÚSQUEDA
     * ========================================================================================== */

    @Test
    @DisplayName("Caso borde: término vacío devuelve el catálogo completo, no un error")
    void terminoVacio() {
        JsonNode page = getJson(PRODUCTS + "?size=50&q=", userToken);

        assertThat(slugs(page)).hasSize(7);
    }

    @Test
    @DisplayName("Caso borde: un solo carácter no rompe la búsqueda")
    void terminoDeUnSoloCaracter() {
        List<String> resultados = buscar("a", "es");

        // 'a' aparece en casi todos los títulos: lo que importa es que responda 200 y filtre por subcadena.
        assertThat(resultados).contains(SLUG_BOTAS);
    }

    @Test
    @DisplayName("Caso borde: una cadena larguísima devuelve vacío, nunca un 500")
    void terminoLarguisimo() {
        String larguisimo = "z".repeat(5000);

        assertThat(buscar(larguisimo, "es")).isEmpty();
    }

    @Test
    @DisplayName("Caso borde: solo signos de puntuación devuelve vacío")
    void terminoSoloPuntuacion() {
        assertThat(buscar("!!!???", "es")).isEmpty();
        assertThat(buscar("---", "es")).isEmpty();
    }

    /**
     * Estuvo desactivado mientras el término del usuario se interpolaba en el {@code LIKE} sin escapar sus
     * comodines: buscar {@code %} devolvía el catálogo entero y {@code %_%} cualquier producto con al menos
     * un carácter. Ya no — {@code Texts.escapeLikeWildcards} neutraliza {@code \}, {@code %} y {@code _}
     * antes de que el término llegue a la consulta, así que el test vuelve a estar activo y es el que
     * impide que la regresión vuelva.
     */
    @Test
    @DisplayName("Caso borde: los comodines de SQL en el término no deben actuar como comodines")
    void terminoConComodinesDeSql() {
        assertThat(buscar("%_%", "es")).isEmpty();
        assertThat(buscar("%", "es")).isEmpty();
    }

    @Test
    @DisplayName("Caso borde: un término que no existe devuelve vacío con el total a cero")
    void terminoInexistente() {
        JsonNode page = getJson(PRODUCTS + "?size=50&lang=es&q=zzzqqqxxx", userToken);

        assertThat(slugs(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("Caso borde: la búsqueda respeta la paginación y sus límites")
    void paginacionDeLaBusqueda() {
        JsonNode primera = getJson(PRODUCTS + "?page=0&size=1&lang=es&q=bota", userToken);
        assertThat(slugs(primera)).hasSize(1);
        assertThat(primera.get("totalElements").asLong()).isEqualTo(2);

        JsonNode masAllaDelFinal = getJson(PRODUCTS + "?page=99&size=1&lang=es&q=bota", userToken);
        assertThat(slugs(masAllaDelFinal)).isEmpty();

        client.get().uri(PRODUCTS + "?size=0&q=bota").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
    }

    /* ============================================================================================
     * FALLBACK A SQL CUANDO OPENSEARCH NO ESTÁ
     * ========================================================================================== */

    @Test
    @DisplayName("Con el buscador caído, el catálogo sigue buscando por SQL (no se deja de vender)")
    void fallbackASqlConOpenSearchCaido() {
        // OpenSearch no está levantado en la suite: searchRelevantIds devuelve vacío y el listado resuelve el
        // texto libre contra la base de datos. Que ESTA búsqueda encuentre algo es la prueba del fallback.
        List<String> resultados = buscar("abrigo", "es");

        assertThat(resultados).contains(SLUG_ABRIGO);
    }

    @Test
    @DisplayName("Con el buscador caído, /api/search se degrada a una respuesta vacía en vez de romperse")
    void endpointDeBusquedaSeDegrada() {
        JsonNode res = getJson(SEARCH + "?q=abrigo", userToken);

        assertThat(res.get("items").size()).isZero();
        assertThat(res.get("total").asLong()).isZero();
    }

    @Test
    @DisplayName("Caso borde: /api/search acota página y tamaño en vez de propagar el error del motor")
    void endpointDeBusquedaAcotaPaginacion() {
        getJson(SEARCH + "?q=abrigo&page=-1&size=0", userToken);
        getJson(SEARCH + "?q=abrigo&page=0&size=100000", userToken);
        getJson(SEARCH + "?q=abrigo&page=-5&size=-5", userToken);
        JsonNode sinTermino = getJson(SEARCH, userToken);

        assertThat(sinTermino.get("items").size()).isZero();
    }

    @Test
    @DisplayName("Caso borde: /api/search sin credencial es 401")
    void endpointDeBusquedaSinCredencial() {
        client.get().uri(SEARCH + "?q=abrigo").exchange().expectStatus().isUnauthorized();
    }

    /* ============================================================================================
     * Utilidades
     * ========================================================================================== */

    /**
     * Lanza la búsqueda del escaparate y devuelve los slugs encontrados, en orden de relevancia.
     *
     * <p>El término viaja como VARIABLE de plantilla ({@code {q}}), nunca concatenado ni pre-codificado con
     * {@code URLEncoder}. Codificarlo a mano era un error de doble codificación: {@code WebTestClient} vuelve
     * a codificar la plantilla y convierte cada {@code %} en {@code %25}, así que el servidor recibía la
     * CADENA LITERAL «%e5%a4%96%e5%a5%97» en vez de «外套» (y «cami%c3%93n» en vez de «CAMIÓN»). Los términos
     * ASCII pasaban intactos, de modo que el fallo solo asomaba en chino y en acentos — y los casos borde de
     * puntuación y comodines pasaban por el motivo equivocado, buscando «%25» en lugar de «%». Con la
     * variable de plantilla, Spring codifica el valor una sola vez y con la regla estricta.
     */
    private List<String> buscar(String termino, String idioma) {
        return slugs(getJson(PRODUCTS + "?size=50&lang={lang}&q={q}", userToken, idioma, termino));
    }

    /** GET que exige 200; {@code vars} rellena las variables de plantilla ({@code {q}}) ya codificadas. */
    private JsonNode getJson(String uriTemplate, String token, Object... vars) {
        JsonNode body = client.get().uri(uriTemplate, vars).header(AUTH, bearer(token)).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).as("cuerpo de %s", uriTemplate).isNotNull();
        return body;
    }

    private static List<String> slugs(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("slug").asText()));
        return out;
    }

    private void clearAllCaches() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /** Repone las divisas que borra el TRUNCATE; sin CNY el pipeline de precios no puede convertir. */
    private void seedCurrencies() {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'USD', 'US Dollar', '$', 'en-US', 1.00, true) "
                + "ON CONFLICT (code) DO NOTHING");
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'CNY', 'Chinese Yuan', '¥', 'zh-CN', 7.24, true) "
                + "ON CONFLICT (code) DO NOTHING");
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, created_at, updated_at, google_linked, "
                + "marketing_opt_out, free_trial_used) VALUES (?, ?, 'USER', true, now(), now(), false, false, false)",
                id, email);
        return id;
    }

    private UUID insertSupplier() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, country) "
                + "VALUES (?, 'SUP-BUSQUEDA', '1688', 'Fabrica de pruebas', 'CN')", id);
        return id;
    }

    private UUID insertCategory() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, slug, name_zh, position, active, source) "
                + "VALUES (?, 'moda', '时尚', 0, true, '1688')", id);
        return id;
    }

    /**
     * Producto visible en el escaparate (ACTIVE + imagen espejada) con su título chino y una traducción por
     * cada idioma del mapa. La descripción se rellena con el propio título para que NO aporte coincidencias
     * inesperadas: las descripciones solo entran en la segunda pasada de la búsqueda.
     */
    private UUID insertProduct(String slug, String tituloZh, Map<String, String> titulos) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, category_id, title_zh, "
                + "status, base_price, currency, rating, monthly_sales, trend_score, ship_from, free_shipping, "
                + "self_pickup, has_video, inventory_count, moq, shipping_cny, iva_cny) "
                + "VALUES (?, ?, ?, '1688', ?, ?, ?, 'ACTIVE', ?, 'CNY', 4.0, 10, 1, 'CN', false, false, false, "
                + "50, 1, 5, 1)",
                id, slug, "EXT-" + slug, proveedor, categoria, tituloZh, new BigDecimal("20.0000"));
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', ?, ?)", id, "https://origen.test/" + slug + ".jpg",
                IMG_CDN + slug + ".jpg");
        for (Map.Entry<String, String> t : titulos.entrySet()) {
            jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                    + "VALUES (gen_random_uuid(), ?, ?, ?, ?)", id, t.getKey(), t.getValue(), t.getValue());
        }
        return id;
    }
}
