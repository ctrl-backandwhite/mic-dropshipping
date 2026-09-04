package com.nexaplatform.dropshipping.integration;

import tools.jackson.databind.JsonNode;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recorrido REAL del catálogo por HTTP: listado con filtros y ordenaciones, ficha, variantes, imágenes por
 * color, favoritos, importación masiva (upsert), borrado y archivado — más los casos borde de paginación y
 * de filtros, que son justo los que se cuelan cuando la suite solo comprueba que el contexto arranca.
 *
 * <p><b>Por qué se siembra con {@code jdbcTemplate} y no por API.</b> El alta de catálogo pasa por el
 * espejado de imágenes y por el indexador, que en un test no tienen ni MinIO ni OpenSearch detrás. Insertando
 * las filas a mano el escenario queda EXACTO y predecible: se sabe qué productos hay, con qué precio, qué
 * nota y qué fecha, así que cada filtro y cada orden se puede afirmar sin depender de servicios externos.
 *
 * <p><b>Visibilidad del escaparate.</b> El SQL del listado exige {@code status = ACTIVE} <em>y</em> al menos
 * una imagen con {@code cdn_url} (espejada). Por eso los productos de la muestra llevan siempre su imagen con
 * CDN, y hay dos señuelos —uno en DRAFT y otro sin espejar— que NUNCA deben aparecer.
 *
 * <p><b>Cachés.</b> El listado y la ficha están cacheados (Caffeine, 5 min) con clave por filtros/idioma/
 * moneda. Como el {@code TRUNCATE} de {@link BaseIntegration} no vacía la caché en memoria, un test podría
 * leer la página que dejó el anterior; por eso se limpian todas las cachés antes de cada prueba.
 *
 * <p><b>Divisas.</b> El pipeline de precios convierte CNY→USD con {@code currency_rate}, tabla que el
 * TRUNCATE deja vacía. Se vuelve a sembrar con los mismos valores que la migración v2 para que el precio
 * mostrado sea estable prueba tras prueba (y para que la conversión no falle si la caché de divisas caduca).
 */
class CatalogFlowIT extends BaseIntegration {

    private static final String PRODUCTS = "/api/catalog/products";
    private static final String ADMIN_CATALOG = "/api/admin/catalog";
    private static final String AUTH = "Authorization";
    private static final String IMG_CDN = "https://cdn.nx036.test/img/";
    private static final String SLUG_BOTAS = "botas-de-agua";
    private static final String SLUG_CAMISETA = "camiseta-basica";
    private static final String SLUG_VESTIDO = "vestido-de-verano";
    private static final String SLUG_BOTONES = "botones-dorados";

    @Autowired
    private CacheManager cacheManager;

    private String userToken;
    private String adminToken;
    private UUID userId;
    private UUID categoriaRopa;
    private UUID categoriaMerceria;
    private UUID proveedor;

    @BeforeEach
    void seedCatalog() {
        clearAllCaches();
        seedCurrencies();
        userId = insertUser("catalogo@nx036.test", "USER");
        userToken = jwt.userToken(userId, "catalogo@nx036.test", "USER");
        adminToken = jwt.userToken("ADMIN");

        proveedor = insertSupplier("SUP-1", "Fábrica Uno");
        categoriaRopa = insertCategory("ropa", "服装", "Ropa");
        categoriaMerceria = insertCategory("merceria", "辅料", "Mercería");

        // Muestra estable: cuatro productos visibles con valores DISTINTOS en cada eje que se filtra u
        // ordena (precio, nota, ventas, tendencia, vídeo, envío gratis, origen), para que ningún filtro
        // pueda "acertar" devolviendo la lista entera.
        Instant ahora = Instant.now();
        newProduct(SLUG_BOTAS, "Botas de agua para lluvia", "Rain boots", categoriaRopa)
                .basePrice("10.0000").rating("4.50").monthlySales(100).trendScore("50")
                .shipFrom("CN").freeShipping(true).hasVideo(false).inventory(10)
                .createdAt(ahora.minus(30, ChronoUnit.DAYS)).insert();
        newProduct(SLUG_CAMISETA, "Camiseta básica de algodón", "Basic t-shirt", categoriaRopa)
                .basePrice("20.0000").rating("3.00").monthlySales(500).trendScore("10")
                .shipFrom("ES").freeShipping(false).hasVideo(true).inventory(5)
                .createdAt(ahora.minus(20, ChronoUnit.DAYS)).insert();
        newProduct(SLUG_VESTIDO, "Vestido de verano", "Summer dress", categoriaMerceria)
                .basePrice("30.0000").rating("5.00").monthlySales(50).trendScore("90")
                .shipFrom("CN").freeShipping(true).hasVideo(false).inventory(0)
                .createdAt(ahora.minus(10, ChronoUnit.DAYS)).insert();
        newProduct(SLUG_BOTONES, "Botones dorados", "Golden buttons", categoriaMerceria)
                .basePrice("5.0000").rating("2.00").monthlySales(1).trendScore("1")
                .shipFrom("CN").freeShipping(false).hasVideo(false).inventory(999)
                .createdAt(ahora.minus(5, ChronoUnit.DAYS)).insert();

        // Señuelos: NINGUNO debe salir en el escaparate.
        newProduct("borrador-invisible", "Borrador invisible", "Draft", categoriaRopa)
                .basePrice("11.0000").status("DRAFT").createdAt(ahora).insert();
        newProduct("sin-espejar", "Sin imagen espejada", "Unmirrored", categoriaRopa)
                .basePrice("12.0000").mirroredImage(false).createdAt(ahora).insert();
    }

    /* ============================================================================================
     * LISTADO — filtros
     * ========================================================================================== */

    @Test
    @DisplayName("El listado solo devuelve productos publicados y con imagen espejada")
    void listadoSoloPublicadosConImagen() {
        JsonNode page = getJson(PRODUCTS + "?size=50", userToken);

        assertThat(slugs(page)).containsExactlyInAnyOrder(SLUG_BOTAS, SLUG_CAMISETA, SLUG_VESTIDO, SLUG_BOTONES);
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("El listado sin credencial es 401 (la enumeración del catálogo está cerrada)")
    void listadoSinCredencialEs401() {
        client.get().uri(PRODUCTS).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Filtro por categoría: solo los productos de esa categoría")
    void filtroPorCategoria() {
        JsonNode page = getJson(PRODUCTS + "?size=50&categoryId=" + categoriaRopa, userToken);

        assertThat(slugs(page)).containsExactlyInAnyOrder(SLUG_BOTAS, SLUG_CAMISETA);
    }

    @Test
    @DisplayName("Caso borde: categoría inexistente devuelve página vacía, no un error")
    void filtroCategoriaInexistente() {
        JsonNode page = getJson(PRODUCTS + "?categoryId=" + UUID.randomUUID(), userToken);

        assertThat(slugs(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("Filtro por precio: acota por el precio que VE el usuario, no por el coste en CNY")
    void filtroPorPrecio() {
        // El rango se construye a partir de los precios REALES devueltos por la API (dependen del margen y
        // de la conversión vigentes), no de números fijos: así el test comprueba el filtro y no el margen.
        JsonNode todos = getJson(PRODUCTS + "?size=50", userToken);
        BigDecimal precioBotones = displayPrice(todos, SLUG_BOTONES);
        BigDecimal precioVestido = displayPrice(todos, SLUG_VESTIDO);
        assertThat(precioBotones).isLessThan(precioVestido);

        JsonNode baratos = getJson(PRODUCTS + "?size=50&maxPrice=" + precioBotones, userToken);
        assertThat(slugs(baratos)).containsExactly(SLUG_BOTONES);

        JsonNode caros = getJson(PRODUCTS + "?size=50&minPrice=" + precioVestido, userToken);
        assertThat(slugs(caros)).containsExactly(SLUG_VESTIDO);
    }

    @Test
    @DisplayName("Caso borde: rango de precio invertido (min > max) devuelve vacío, nunca 500")
    void filtroPrecioInvertido() {
        JsonNode page = getJson(PRODUCTS + "?size=50&minPrice=9999&maxPrice=1", userToken);

        assertThat(slugs(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
    }

    @Test
    @DisplayName("Caso borde: precios negativos no rompen el listado")
    void filtroPrecioNegativo() {
        // minPrice negativo no excluye a nadie; maxPrice negativo excluye a todos. Ninguno es un 500.
        JsonNode conMinNegativo = getJson(PRODUCTS + "?size=50&minPrice=-100", userToken);
        assertThat(slugs(conMinNegativo)).hasSize(4);

        JsonNode conMaxNegativo = getJson(PRODUCTS + "?size=50&maxPrice=-1", userToken);
        assertThat(slugs(conMaxNegativo)).isEmpty();
    }

    @Test
    @DisplayName("Filtro por envío gratis, vídeo, nota mínima y país de origen")
    void filtrosDeFacetas() {
        assertThat(slugs(getJson(PRODUCTS + "?size=50&freeShipping=true", userToken)))
                .containsExactlyInAnyOrder(SLUG_BOTAS, SLUG_VESTIDO);
        assertThat(slugs(getJson(PRODUCTS + "?size=50&hasVideo=true", userToken)))
                .containsExactly(SLUG_CAMISETA);
        assertThat(slugs(getJson(PRODUCTS + "?size=50&minRating=4", userToken)))
                .containsExactlyInAnyOrder(SLUG_BOTAS, SLUG_VESTIDO);
        // El código de país se normaliza a mayúsculas en el servidor: "cn" tiene que valer igual que "CN".
        assertThat(slugs(getJson(PRODUCTS + "?size=50&shipFrom=cn", userToken)))
                .containsExactlyInAnyOrder(SLUG_BOTAS, SLUG_VESTIDO, SLUG_BOTONES);
        assertThat(slugs(getJson(PRODUCTS + "?size=50&inventoryMin=100", userToken)))
                .containsExactly(SLUG_BOTONES);
    }

    @Test
    @DisplayName("Caso borde: todos los filtros a la vez devuelven el único producto que los cumple")
    void todosLosFiltrosALaVez() {
        JsonNode todos = getJson(PRODUCTS + "?size=50", userToken);
        BigDecimal precioBotas = displayPrice(todos, SLUG_BOTAS);

        String uri = PRODUCTS + "?size=50&lang=es&q=botas&categoryId=" + categoriaRopa + "&supplierId=" + proveedor
                + "&minPrice=" + precioBotas + "&maxPrice=" + precioBotas
                + "&shipFrom=CN&freeShipping=true&selfPickup=false&hasVideo=false&minRating=4&inventoryMin=1"
                + "&sort=price_asc";

        assertThat(slugs(getJson(uri, userToken))).containsExactly(SLUG_BOTAS);
    }

    @Test
    @DisplayName("Caso borde: una combinación de filtros imposible devuelve vacío, no un error")
    void combinacionImposibleDeFiltros() {
        String uri = PRODUCTS + "?size=50&categoryId=" + categoriaRopa
                + "&hasVideo=true&freeShipping=true&minRating=5&shipFrom=JP";

        assertThat(slugs(getJson(uri, userToken))).isEmpty();
    }

    /* ============================================================================================
     * LISTADO — ordenaciones
     * ========================================================================================== */

    @Test
    @DisplayName("Ordenación por precio ascendente y descendente sobre el precio mostrado")
    void ordenPorPrecio() {
        List<BigDecimal> asc = displayPrices(getJson(PRODUCTS + "?size=50&sort=price_asc", userToken));
        assertThat(asc).isSorted();

        List<BigDecimal> desc = displayPrices(getJson(PRODUCTS + "?size=50&sort=price_desc", userToken));
        assertThat(desc).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    /**
     * La semilla baraja el catálogo, y solo contra una base de datos real se sabe si funciona.
     *
     * <p>El desempate sembrado es una expresión SQL construida a mano —{@code md5} sobre el id más la
     * baraja—, así que las pruebas unitarias solo pueden comprobar que se arma bien: si Hibernate no
     * supiera traducirla, o PostgreSQL la rechazara, el listado entero devolvería un 500 y ningún test de
     * unidad se enteraría. Por eso este caso pide el listado de verdad.
     *
     * <p>Comprueba además lo que sostiene el scroll infinito: la misma semilla devuelve SIEMPRE el mismo
     * orden. Si no, al bajar por el catálogo saldrían productos repetidos y otros no saldrían nunca.
     */
    @Test
    @DisplayName("Con semilla el catálogo se baraja, pero la misma semilla da siempre el mismo orden")
    void elOrdenSeBarajaConLaSemilla() {
        List<String> conSemilla = slugs(getJson(PRODUCTS + "?size=50&seed=7", userToken));
        assertThat(conSemilla).hasSize(4);
        assertThat(conSemilla).isEqualTo(slugs(getJson(PRODUCTS + "?size=50&seed=7", userToken)));

        // Sin semilla se mantiene el orden fijo de siempre: quien no la manda no nota el cambio.
        assertThat(slugs(getJson(PRODUCTS + "?size=50", userToken)))
                .containsExactly(SLUG_VESTIDO, SLUG_BOTAS, SLUG_CAMISETA, SLUG_BOTONES);

        // Una semilla negativa o desbordada tampoco rompe la consulta: se reduce al rango de barajas.
        assertThat(slugs(getJson(PRODUCTS + "?size=50&seed=-1", userToken))).hasSize(4);
        assertThat(slugs(getJson(PRODUCTS + "?size=50&seed=2147483647", userToken))).hasSize(4);

        // Y el criterio sigue mandando sobre la baraja: con semilla, «precio ascendente» ordena por precio.
        assertThat(displayPrices(getJson(PRODUCTS + "?size=50&sort=price_asc&seed=13", userToken))).isSorted();
    }

    @Test
    @DisplayName("Ordenación por novedad, por ventas y por relevancia (tendencia) por defecto")
    void ordenPorNovedadVentasYRelevancia() {
        // Novedad: el sembrado da fechas descendentes conocidas, así que el orden es exacto.
        assertThat(slugs(getJson(PRODUCTS + "?size=50&sort=newest", userToken)))
                .containsExactly(SLUG_BOTONES, SLUG_VESTIDO, SLUG_CAMISETA, SLUG_BOTAS);
        // Ventas mensuales: 500 > 100 > 50 > 1.
        assertThat(slugs(getJson(PRODUCTS + "?size=50&sort=sales", userToken)))
                .containsExactly(SLUG_CAMISETA, SLUG_BOTAS, SLUG_VESTIDO, SLUG_BOTONES);
        // Sin `sort` manda best_match, que sin texto libre equivale a tendencia: 90 > 50 > 10 > 1.
        assertThat(slugs(getJson(PRODUCTS + "?size=50", userToken)))
                .containsExactly(SLUG_VESTIDO, SLUG_BOTAS, SLUG_CAMISETA, SLUG_BOTONES);
    }

    @Test
    @DisplayName("Caso borde: un criterio de orden desconocido cae en el orden por defecto, no falla")
    void ordenDesconocido() {
        assertThat(slugs(getJson(PRODUCTS + "?size=50&sort=orden-que-no-existe", userToken)))
                .containsExactly(SLUG_VESTIDO, SLUG_BOTAS, SLUG_CAMISETA, SLUG_BOTONES);
    }

    /* ============================================================================================
     * LISTADO — paginación (casos borde)
     * ========================================================================================== */

    @Test
    @DisplayName("Paginación normal: la suma de páginas es el catálogo completo, sin repetidos")
    void paginacionNormal() {
        JsonNode primera = getJson(PRODUCTS + "?page=0&size=2&sort=newest", userToken);
        JsonNode segunda = getJson(PRODUCTS + "?page=1&size=2&sort=newest", userToken);

        assertThat(slugs(primera)).hasSize(2);
        assertThat(slugs(segunda)).hasSize(2);
        assertThat(slugs(primera)).doesNotContainAnyElementsOf(slugs(segunda));
        assertThat(primera.get("totalElements").asLong()).isEqualTo(4);
        assertThat(primera.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Caso borde: página -1 se acota a la primera página en vez de reventar")
    void paginaNegativa() {
        JsonNode page = getJson(PRODUCTS + "?page=-1&size=2&sort=newest", userToken);

        assertThat(page.get("page").asInt()).isZero();
        assertThat(slugs(page)).hasSize(2);
    }

    @Test
    @DisplayName("Caso borde: tamaño de página 0 se rechaza con 400, nunca con 500")
    void tamanoDePaginaCero() {
        client.get().uri(PRODUCTS + "?size=0").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Caso borde: tamaño de página negativo se rechaza con 400, nunca con 500")
    void tamanoDePaginaNegativo() {
        client.get().uri(PRODUCTS + "?size=-5").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Caso borde: un tamaño de página enorme se acota a 100")
    void tamanoDePaginaEnorme() {
        JsonNode page = getJson(PRODUCTS + "?size=100000", userToken);

        assertThat(page.get("size").asInt()).isEqualTo(100);
        assertThat(slugs(page)).hasSize(4);
    }

    @Test
    @DisplayName("Caso borde: una página más allá del final devuelve vacío con el total correcto")
    void paginaMasAllaDelFinal() {
        JsonNode page = getJson(PRODUCTS + "?page=999&size=10", userToken);

        assertThat(slugs(page)).isEmpty();
        assertThat(page.get("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("Caso borde: una página desmesurada (desbordaría el offset) devuelve vacío, no 500")
    void paginaDesmesurada() {
        JsonNode page = getJson(PRODUCTS + "?page=2000000000&size=100", userToken);

        assertThat(slugs(page)).isEmpty();
    }

    @Test
    @DisplayName("Caso borde: page y size no numéricos se rechazan con 400")
    void paginacionNoNumerica() {
        client.get().uri(PRODUCTS + "?page=abc").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
        client.get().uri(PRODUCTS + "?size=diez").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Caso borde: un categoryId que no es UUID se rechaza con 400")
    void categoriaNoUuid() {
        client.get().uri(PRODUCTS + "?categoryId=no-es-un-uuid").header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isBadRequest();
    }

    /* ============================================================================================
     * FICHA DE PRODUCTO
     * ========================================================================================== */

    @Test
    @DisplayName("Ficha por slug: devuelve el título del idioma pedido y sus imágenes")
    void fichaPorSlug() {
        JsonNode es = getJson("/api/catalog/products/" + SLUG_BOTAS + "?lang=es", null);
        assertThat(es.get("title").asText()).isEqualTo("Botas de agua para lluvia");
        assertThat(es.get("images").size()).isPositive();
        assertThat(es.get("images").get(0).get("cdnUrl").asText()).startsWith(IMG_CDN);

        JsonNode en = getJson("/api/catalog/products/" + SLUG_BOTAS + "?lang=en", null);
        assertThat(en.get("title").asText()).isEqualTo("Rain boots");
    }

    @Test
    @DisplayName("Ficha por id y por identificador externo")
    void fichaPorIdYPorExterno() {
        UUID id = productId(SLUG_BOTAS);

        JsonNode porId = getJson("/api/catalog/products/by-id/" + id, null);
        assertThat(porId.get("slug").asText()).isEqualTo(SLUG_BOTAS);

        JsonNode porExterno = getJson("/api/catalog/products/by-external/1688/EXT-" + SLUG_BOTAS, null);
        assertThat(porExterno.get("id").asText()).isEqualTo(id.toString());
    }

    @Test
    @DisplayName("Caso borde: ficha inexistente devuelve 404 y un id mal formado 400")
    void fichaInexistente() {
        client.get().uri("/api/catalog/products/by-id/" + UUID.randomUUID()).exchange()
                .expectStatus().isNotFound();
        client.get().uri("/api/catalog/products/by-id/no-es-uuid").exchange()
                .expectStatus().isBadRequest();
        client.get().uri("/api/catalog/products/by-external/1688/NO-EXISTE").exchange()
                .expectStatus().isNotFound();
    }

    /* ============================================================================================
     * VARIANTES E IMÁGENES POR COLOR
     * ========================================================================================== */

    @Test
    @DisplayName("Variantes de un producto: se sirve el precio de VENTA, jamás el coste del proveedor")
    void variantesDevuelvenPrecioDeVenta() {
        UUID id = productId(SLUG_BOTAS);
        insertVariant(id, "SKU-ROJO", "Rojo", "120.0000", 7, Map.of("Color", "Rojo"));

        JsonNode variantes = getJson(PRODUCTS + "/" + id + "/variants", userToken);

        assertThat(variantes.size()).isEqualTo(1);
        JsonNode v = variantes.get(0);
        assertThat(v.get("sku").asText()).isEqualTo("SKU-ROJO");
        assertThat(v.get("stock").asInt()).isEqualTo(7);
        // El coste del proveedor son 120 CNY. Lo que se publica es el precio de venta en la divisa del
        // usuario, así que no puede coincidir con ese número; y no hay ningún campo de coste ni de margen.
        assertThat(new BigDecimal(v.get("price").asText())).isNotEqualByComparingTo(new BigDecimal("120"));
        assertThat(camposDe(v)).doesNotContain("cost", "costUsd", "basePrice", "priceCny", "margin",
                "appliedMarginPercent");
    }

    @Test
    @DisplayName("Variante por SKU y caso borde de SKU inexistente (404)")
    void variantePorSku() {
        UUID id = productId(SLUG_BOTAS);
        insertVariant(id, "SKU-AZUL", "Azul", "99.0000", 3, Map.of("Color", "Azul"));

        JsonNode v = getJson(PRODUCTS + "/" + id + "/variants/by-sku/SKU-AZUL", userToken);
        assertThat(v.get("options").get("Color").asText()).isEqualTo("Azul");

        client.get().uri(PRODUCTS + "/" + id + "/variants/by-sku/NO-EXISTE")
                .header(AUTH, bearer(userToken)).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Imágenes por color: cada valor del eje Color lleva su foto real en la ficha")
    void imagenesPorColor() {
        UUID id = productId(SLUG_BOTAS);
        UUID eje = insertVariantOption(id, "颜色", "Color");
        insertVariantValue(eje, "红色", "Rojo", IMG_CDN + "rojo.jpg", 0);
        insertVariantValue(eje, "蓝色", "Azul", IMG_CDN + "azul.jpg", 1);

        JsonNode ficha = getJson("/api/catalog/products/" + SLUG_BOTAS + "?lang=es", null);
        JsonNode valores = ficha.get("variantOptions").get(0).get("values");

        assertThat(valores.size()).isEqualTo(2);
        assertThat(valores.get(0).get("imageUrl").asText()).isEqualTo(IMG_CDN + "rojo.jpg");
        assertThat(valores.get(1).get("imageUrl").asText()).isEqualTo(IMG_CDN + "azul.jpg");
    }

    @Test
    @DisplayName("Imágenes del producto: el endpoint devuelve la galería en orden")
    void imagenesDelProducto() {
        UUID id = productId(SLUG_BOTAS);
        insertImage(id, 1, "GALLERY", IMG_CDN + SLUG_BOTAS + "-2.jpg");

        JsonNode imagenes = getJson(PRODUCTS + "/" + id + "/images", userToken);

        assertThat(imagenes.size()).isEqualTo(2);
        assertThat(imagenes.get(0).get("position").asInt()).isZero();
        assertThat(imagenes.get(1).get("position").asInt()).isEqualTo(1);
    }

    /* ============================================================================================
     * COSTE EN CNY Y MARGEN — nunca para el usuario final
     * ========================================================================================== */

    @Test
    @DisplayName("La ficha NO expone coste ni margen a un usuario normal, y sí al admin")
    void costeYMargenSoloParaAdmin() {
        JsonNode paraUsuario = getJson("/api/catalog/products/" + SLUG_BOTAS, userToken);
        assertThat(paraUsuario.get("costUsd").isNull()).isTrue();
        assertThat(paraUsuario.get("retailUsd").isNull()).isTrue();
        assertThat(paraUsuario.get("appliedMarginPercent").isNull()).isTrue();
        assertThat(paraUsuario.get("baseFormatted").isNull()).isTrue();
        assertThat(paraUsuario.get("ivaFormatted").isNull()).isTrue();
        assertThat(paraUsuario.get("shippingFormatted").isNull()).isTrue();
        // Y sí ve el precio de venta, que es lo único que necesita para comprar.
        assertThat(paraUsuario.get("displayFormatted").asText()).isNotBlank();

        clearAllCaches(); // la ficha se cachea con la condición de admin en la clave; se evita cualquier duda
        JsonNode paraAdmin = getJson("/api/catalog/products/" + SLUG_BOTAS, adminToken);
        assertThat(paraAdmin.get("costUsd").isNull()).isFalse();
        assertThat(paraAdmin.get("appliedMarginPercent").isNull()).isFalse();
    }

    /**
     * Hermano del caso del listado, y cerrado con el mismo criterio: {@code ProductMapper.toDetail} filtraba
     * por rol {@code costUsd}, {@code retailUsd} y el % de margen, pero dejaba pasar {@code basePrice} (lo que
     * se paga al proveedor en CNY) y {@code currency}='CNY'. Tapar el listado y dejar la ficha abierta no
     * servía de nada: a la ficha se llega por enlace directo. Hoy esos dos campos solo viajan para ADMIN.
     */
    @Test
    @DisplayName("La ficha tampoco debe publicar el coste en CNY del proveedor")
    void fichaNoDeberiaPublicarCosteCny() {
        JsonNode paraUsuario = getJson("/api/catalog/products/" + SLUG_BOTAS, userToken);

        assertThat(paraUsuario.get("basePrice") == null || paraUsuario.get("basePrice").isNull()).isTrue();
        assertThat(paraUsuario.get("currency") == null || paraUsuario.get("currency").isNull()).isTrue();
        // Y sigue viendo lo único que necesita para comprar: el precio de venta ya formateado.
        assertThat(paraUsuario.get("displayFormatted").asText()).isNotBlank();

        // El editor del admin tarifica con el coste: ocultárselo a todo el mundo rompería el panel.
        clearAllCaches();
        JsonNode paraAdmin = getJson("/api/catalog/products/" + SLUG_BOTAS, adminToken);
        assertThat(paraAdmin.get("basePrice").isNull()).isFalse();
        assertThat(paraAdmin.get("currency").asText()).isEqualTo("CNY");
    }

    @Test
    @DisplayName("El estimador de margen es exclusivo del admin (403 para un usuario normal)")
    void estimadorDeMargenSoloAdmin() {
        UUID id = productId(SLUG_BOTAS);

        client.get().uri(PRODUCTS + "/" + id + "/margin-estimate").header(AUTH, bearer(userToken))
                .exchange().expectStatus().isForbidden();
        client.get().uri(PRODUCTS + "/" + id + "/margin-estimate").header(AUTH, bearer(adminToken))
                .exchange().expectStatus().isOk();
    }

    /**
     * Estuvo desactivado mientras {@code ProductSummaryView} publicaba {@code basePrice} (el coste del
     * proveedor en CNY) y {@code currency='CNY'} a cualquier consumidor: con el precio de venta al lado, el
     * margen quedaba a la vista con una división. {@code ProductMapper.toSummary} ya solo sirve esos campos
     * —y {@code priceUsd}— al ADMIN, así que el test vuelve a estar activo.
     */
    @Test
    @DisplayName("El listado NO debe publicar el coste en CNY del proveedor")
    void listadoNoDebePublicarCosteCny() {
        JsonNode page = getJson(PRODUCTS + "?size=50", userToken);
        JsonNode botas = itemPorSlug(page, SLUG_BOTAS);

        assertThat(botas.get("basePrice") == null || botas.get("basePrice").isNull()).isTrue();
        assertThat(botas.get("currency") == null || botas.get("currency").isNull()).isTrue();
        assertThat(botas.get("priceUsd") == null || botas.get("priceUsd").isNull()).isTrue();
        // Y el admin sí los ve: ocultarlos a todo el mundo rompería el panel, que tarifica con ellos.
        clearAllCaches();
        JsonNode paraAdmin = itemPorSlug(getJson(PRODUCTS + "?size=50", adminToken), SLUG_BOTAS);
        assertThat(paraAdmin.get("basePrice").isNull()).isFalse();
        assertThat(paraAdmin.get("currency").asText()).isEqualTo("CNY");
    }

    /**
     * El coste del proveedor no puede colarse por la CACHÉ. El listado se cachea con una clave que llevaba
     * moneda, canal, país y filtros, pero NO el rol: la página que rellenaba un admin —con {@code basePrice},
     * {@code currency} y {@code priceUsd} dentro— se servía tal cual al siguiente usuario del escaparate que
     * pidiera los mismos filtros. La ficha ya metía {@code isAdmin()} en su clave; el listado no.
     */
    @Test
    @DisplayName("El coste no se filtra por la caché: lo que pidió un admin no se sirve a un usuario")
    void costeNoSeFiltraPorLaCacheDelListado() {
        // El admin puebla la caché del listado con los campos de coste dentro.
        JsonNode paraAdmin = itemPorSlug(getJson(PRODUCTS + "?size=50", adminToken), SLUG_BOTAS);
        assertThat(paraAdmin.get("basePrice").isNull()).isFalse();

        // MISMOS filtros y SIN limpiar cachés: el usuario normal no puede recibir la entrada del admin.
        JsonNode paraUsuario = itemPorSlug(getJson(PRODUCTS + "?size=50", userToken), SLUG_BOTAS);

        assertThat(paraUsuario.get("basePrice") == null || paraUsuario.get("basePrice").isNull()).isTrue();
        assertThat(paraUsuario.get("currency") == null || paraUsuario.get("currency").isNull()).isTrue();
        assertThat(paraUsuario.get("priceUsd") == null || paraUsuario.get("priceUsd").isNull()).isTrue();
    }

    /* ============================================================================================
     * FAVORITOS
     * ========================================================================================== */

    @Test
    @DisplayName("Favoritos: marcar, listar, desmarcar — y todo idempotente")
    void favoritos() {
        UUID id = productId(SLUG_BOTAS);

        // Marcar dos veces no duplica.
        postEmpty("/api/me/favorites/" + id, userToken).expectStatus().isOk();
        postEmpty("/api/me/favorites/" + id, userToken).expectStatus().isOk();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product_favorite WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);

        JsonNode ids = getJson("/api/me/favorites/ids", userToken);
        assertThat(ids.size()).isEqualTo(1);
        assertThat(ids.get(0).asText()).isEqualTo(id.toString());

        JsonNode lista = getJson("/api/me/favorites", userToken);
        assertThat(slugs(lista)).containsExactly(SLUG_BOTAS);

        // Desmarcar dos veces tampoco falla.
        client.delete().uri("/api/me/favorites/" + id).header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isOk();
        client.delete().uri("/api/me/favorites/" + id).header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isOk();
        assertThat(getJson("/api/me/favorites/ids", userToken).size()).isZero();
    }

    @Test
    @DisplayName("Caso borde: marcar como favorito un producto inexistente devuelve 404")
    void favoritoDeProductoInexistente() {
        postEmpty("/api/me/favorites/" + UUID.randomUUID(), userToken).expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Caso borde: favoritos sin credencial es 401")
    void favoritosSinCredencial() {
        client.get().uri("/api/me/favorites/ids").exchange().expectStatus().isUnauthorized();
    }

    /* ============================================================================================
     * IMPORTACIÓN MASIVA (bulk upsert)
     * ========================================================================================== */

    @Test
    @DisplayName("Importación masiva: una fila válida crea el producto con su identificador externo")
    void bulkCreaProducto() {
        JsonNode res = bulk("[" + filaValida("BULK-A", "Mochila de viaje", "12.50") + "]");

        assertThat(res.get("created").asInt()).isEqualTo(1);
        assertThat(res.get("failed").asInt()).isZero();
        assertThat(contarProductos("BULK-A")).isEqualTo(1);
    }

    @Test
    @DisplayName("Importación masiva: reimportar el MISMO external_id actualiza en sitio (mismo id, sin duplicar)")
    void bulkReimportarActualizaEnSitio() {
        bulk("[" + filaValida("BULK-UPSERT", "Mochila de viaje", "12.50") + "]");
        UUID idOriginal = idPorExternalId("BULK-UPSERT");

        JsonNode res = bulk("[" + filaValida("BULK-UPSERT", "Mochila de viaje XL", "19.90") + "]");

        assertThat(res.get("failed").asInt()).isZero();
        assertThat(contarProductos("BULK-UPSERT")).as("no debe duplicarse el producto").isEqualTo(1);
        assertThat(idPorExternalId("BULK-UPSERT")).as("el id NO puede cambiar: cuelgan favoritos y pedidos")
                .isEqualTo(idOriginal);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM product_translation WHERE product_id = ? AND language = 'es'",
                String.class, idOriginal)).isEqualTo("Mochila de viaje XL");
    }

    @Test
    @DisplayName("Caso borde: una fila SIN imágenes se rechaza con su motivo y no entra en el catálogo")
    void bulkFilaSinImagenes() {
        String fila = """
                {"categorySlug":"ropa","externalId":"BULK-SIN-IMG","titleEs":"Producto sin foto",
                 "price":10,"shippingCny":5,"ivaCny":1}
                """;

        JsonNode res = bulk("[" + fila + "]");

        assertThat(res.get("created").asInt()).isZero();
        assertThat(res.get("failed").asInt()).isEqualTo(1);
        assertThat(res.get("errors").get(0).asText()).contains("imágenes");
        assertThat(contarProductos("BULK-SIN-IMG")).isZero();
    }

    @Test
    @DisplayName("Caso borde: una fila SIN variantes es válida (las variantes son opcionales)")
    void bulkFilaSinVariantes() {
        JsonNode res = bulk("[" + filaValida("BULK-SIN-VAR", "Producto simple", "8.00") + "]");

        assertThat(res.get("created").asInt()).isEqualTo(1);
        assertThat(contarProductos("BULK-SIN-VAR")).isEqualTo(1);
    }

    @Test
    @DisplayName("Caso borde: un lote vacío no crea ni falla nada")
    void bulkLoteVacio() {
        JsonNode res = bulk("[]");

        assertThat(res.get("created").asInt()).isZero();
        assertThat(res.get("failed").asInt()).isZero();
    }

    @Test
    @DisplayName("Caso borde: una fila inválida entre válidas no aborta el lote")
    void bulkFilaInvalidaEntreValidas() {
        String invalida = """
                {"categorySlug":"ropa","externalId":"BULK-MALA","titleEs":"Sin precio ni envío",
                 "imageUrls":["https://cdn.nx036.test/img/x.jpg"]}
                """;
        String lote = "[" + filaValida("BULK-OK-1", "Primera", "10.00") + "," + invalida + ","
                + filaValida("BULK-OK-2", "Tercera", "11.00") + "]";

        JsonNode res = bulk(lote);

        assertThat(res.get("created").asInt()).isEqualTo(2);
        assertThat(res.get("failed").asInt()).isEqualTo(1);
        assertThat(res.get("errors").get(0).asText()).startsWith("Fila 2");
        assertThat(contarProductos("BULK-OK-1")).isEqualTo(1);
        assertThat(contarProductos("BULK-OK-2")).isEqualTo(1);
        assertThat(contarProductos("BULK-MALA")).isZero();
    }

    @Test
    @DisplayName("Caso borde: una categoría inexistente en la fila la rechaza con mensaje claro")
    void bulkCategoriaInexistente() {
        String fila = """
                {"categorySlug":"no-existe","externalId":"BULK-CAT","titleEs":"Producto",
                 "price":10,"shippingCny":5,"ivaCny":1,"imageUrls":["https://cdn.nx036.test/img/x.jpg"]}
                """;

        JsonNode res = bulk("[" + fila + "]");

        assertThat(res.get("failed").asInt()).isEqualTo(1);
        assertThat(res.get("errors").get(0).asText()).contains("Categoría no encontrada");
    }

    @Test
    @DisplayName("La importación masiva es exclusiva del admin: un usuario normal recibe 403")
    void bulkSoloAdmin() {
        client.post().uri(ADMIN_CATALOG + "/products/bulk").header(AUTH, bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("[]").exchange()
                .expectStatus().isForbidden();
    }

    /* ============================================================================================
     * BORRADO Y ARCHIVADO
     * ========================================================================================== */

    @Test
    @DisplayName("Borrado: el producto desaparece del catálogo y de la base de datos")
    void borradoDeProducto() {
        UUID id = productId(SLUG_BOTONES);

        client.delete().uri(ADMIN_CATALOG + "/products/" + id).header(AUTH, bearer(adminToken))
                .exchange().expectStatus().isNoContent();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE id = ?", Integer.class, id))
                .isZero();
        clearAllCaches();
        assertThat(slugs(getJson(PRODUCTS + "?size=50", userToken))).doesNotContain(SLUG_BOTONES);
    }

    @Test
    @DisplayName("Archivado: un producto ARCHIVED deja de listarse en el escaparate")
    void archivadoDeProducto() {
        UUID id = productId(SLUG_VESTIDO);

        client.put().uri(ADMIN_CATALOG + "/products/" + id + "/status").header(AUTH, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"status\":\"ARCHIVED\"}")
                .exchange().expectStatus().is2xxSuccessful();

        clearAllCaches();
        assertThat(slugs(getJson(PRODUCTS + "?size=50", userToken))).doesNotContain(SLUG_VESTIDO);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM product WHERE id = ?", String.class, id))
                .isEqualTo("ARCHIVED");
    }

    /**
     * Un producto retirado tiene que dejar de existir para el escaparate por TODAS sus puertas, no solo por
     * el listado. El listado ya exigía {@code ACTIVE} y el cobro ya devolvía {@code PRODUCT_UNAVAILABLE},
     * pero la ficha por slug —a la que se llega con un enlace directo, un resultado indexado o un correo
     * viejo— se seguía sirviendo entera y con su precio: enseñaba el escaparate de algo que no se puede
     * comprar. El admin sí tiene que poder abrirla, porque desde el panel se revisa y se reactiva justo lo
     * que está retirado.
     */
    @Test
    @DisplayName("La ficha de un producto retirado responde 404 al usuario y al anónimo, y 200 al admin")
    void fichaDeProductoRetiradoEs404SalvoParaElAdmin() {
        String ficha = PRODUCTS + "/" + SLUG_BOTAS;
        for (String estado : List.of("PAUSED", "ARCHIVED", "DRAFT")) {
            jdbcTemplate.update("UPDATE product SET status = ? WHERE slug = ?", estado, SLUG_BOTAS);
            // La ficha está cacheada y el TRUNCATE/UPDATE por SQL no invalida Caffeine: sin limpiar, la
            // vuelta siguiente del bucle leería la respuesta del estado anterior.
            clearAllCaches();

            client.get().uri(ficha).exchange().expectStatus().isNotFound();
            client.get().uri(ficha).header(AUTH, bearer(userToken)).exchange().expectStatus().isNotFound();
            assertThat(getJson(ficha, adminToken).get("status").asText())
                    .as("el admin sigue viendo la ficha del producto en %s", estado).isEqualTo(estado);
        }

        // Y al reactivarlo vuelve a estar abierta para todo el mundo.
        jdbcTemplate.update("UPDATE product SET status = 'ACTIVE' WHERE slug = ?", SLUG_BOTAS);
        clearAllCaches();
        assertThat(getJson(ficha, null).get("slug").asText()).isEqualTo(SLUG_BOTAS);
    }

    @Test
    @DisplayName("Archivado en lote: varios productos a la vez, con el recuento de resultados")
    void archivadoEnLote() {
        UUID uno = productId(SLUG_BOTAS);
        UUID dos = productId(SLUG_CAMISETA);

        JsonNode res = client.put().uri(ADMIN_CATALOG + "/products/bulk-status").header(AUTH, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"ids\":[\"" + uno + "\",\"" + dos + "\"],\"status\":\"ARCHIVED\"}")
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(res).isNotNull();
        assertThat(res.get("succeeded").asInt()).isEqualTo(2);
        clearAllCaches();
        assertThat(slugs(getJson(PRODUCTS + "?size=50", userToken)))
                .containsExactlyInAnyOrder(SLUG_VESTIDO, SLUG_BOTONES);
    }

    @Test
    @DisplayName("Caso borde: borrar un producto inexistente devuelve 404, no 500")
    void borradoDeProductoInexistente() {
        client.delete().uri(ADMIN_CATALOG + "/products/" + UUID.randomUUID())
                .header(AUTH, bearer(adminToken)).exchange().expectStatus().isNotFound();
    }

    /* ============================================================================================
     * Utilidades de petición
     * ========================================================================================== */

    /** GET que exige 200 y devuelve el cuerpo como árbol JSON; {@code token} nulo = petición anónima. */
    private JsonNode getJson(String uri, String token) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (token != null) {
            spec = spec.header(AUTH, bearer(token));
        }
        JsonNode body = spec.exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult()
                .getResponseBody();
        assertThat(body).as("cuerpo de %s", uri).isNotNull();
        return body;
    }

    private WebTestClient.ResponseSpec postEmpty(String uri, String token) {
        return client.post().uri(uri).header(AUTH, bearer(token)).exchange();
    }

    /** POST del lote de importación masiva como admin; devuelve el {@code BulkResultDtoOut}. */
    private JsonNode bulk(String jsonArray) {
        JsonNode body = client.post().uri(ADMIN_CATALOG + "/products/bulk").header(AUTH, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(jsonArray).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** Fila mínima que cumple TODAS las reglas de calidad: categoría, título, precio, envío, IVA e imagen. */
    private static String filaValida(String externalId, String titulo, String precio) {
        return """
                {"categorySlug":"ropa","externalId":"%s","titleEs":"%s","price":%s,
                 "shippingCny":5,"ivaCny":1,"imageUrls":["https://cdn.nx036.test/img/%s.jpg"]}
                """.formatted(externalId, titulo, precio, externalId);
    }

    /* ============================================================================================
     * Utilidades de lectura del JSON
     * ========================================================================================== */

    /** Slugs de una página ({@code items}) o de una lista suelta de resúmenes. */
    private static List<String> slugs(JsonNode pageOrList) {
        JsonNode items = pageOrList.has("items") ? pageOrList.get("items") : pageOrList;
        List<String> out = new ArrayList<>();
        items.forEach(n -> out.add(n.get("slug").asText()));
        return out;
    }

    private static List<BigDecimal> displayPrices(JsonNode page) {
        List<BigDecimal> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(new BigDecimal(n.get("displayPrice").asText())));
        return out;
    }

    private static BigDecimal displayPrice(JsonNode page, String slug) {
        return new BigDecimal(itemPorSlug(page, slug).get("displayPrice").asText());
    }

    private static JsonNode itemPorSlug(JsonNode page, String slug) {
        for (JsonNode n : page.get("items")) {
            if (slug.equals(n.get("slug").asText())) {
                return n;
            }
        }
        throw new AssertionError("No está en la página el producto " + slug);
    }

    private static List<String> camposDe(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    /* ============================================================================================
     * Sembrado
     * ========================================================================================== */

    private void clearAllCaches() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * Repone las divisas que el TRUNCATE se lleva por delante. Sin la fila de CNY la conversión a USD
     * lanza «Unknown source currency», así que el catálogo entero dejaría de tarificar.
     */
    private void seedCurrencies() {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'USD', 'US Dollar', '$', 'en-US', 1.00, true) "
                + "ON CONFLICT (code) DO NOTHING");
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'CNY', 'Chinese Yuan', '¥', 'zh-CN', 7.24, true) "
                + "ON CONFLICT (code) DO NOTHING");
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), 'EUR', 'Euro', '€', 'es-ES', 0.92, true) "
                + "ON CONFLICT (code) DO NOTHING");
    }

    private UUID insertUser(String email, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, created_at, updated_at, google_linked, "
                + "marketing_opt_out, free_trial_used) VALUES (?, ?, ?, true, now(), now(), false, false, false)",
                id, email, role);
        return id;
    }

    private UUID insertSupplier(String externalId, String nombre) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, country, rating, years_active, "
                + "verified, trust_pass) VALUES (?, ?, '1688', ?, 'CN', 4.8, 5, true, true)", id, externalId, nombre);
        return id;
    }

    private UUID insertCategory(String slug, String nameZh, String nombreEs) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, slug, name_zh, position, active, source) "
                + "VALUES (?, ?, ?, 0, true, '1688')", id, slug, nameZh);
        jdbcTemplate.update("INSERT INTO category_translation (id, category_id, language, name) "
                + "VALUES (gen_random_uuid(), ?, 'es', ?)", id, nombreEs);
        return id;
    }

    private void insertImage(UUID productId, int position, String role, String cdnUrl) {
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)", productId, position, role,
                "https://origen.test/" + productId + "-" + position + ".jpg", cdnUrl);
    }

    private void insertVariant(UUID productId, String sku, String titulo, String precioCny, int stock,
            Map<String, String> opciones) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : opciones.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            first = false;
        }
        json.append('}');
        jdbcTemplate.update("INSERT INTO product_variant (id, product_id, external_id, sku, title, price, stock, "
                + "options_json, active) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), true)",
                productId, "EXT-" + sku, sku, titulo, new BigDecimal(precioCny), stock, json.toString());
    }

    private UUID insertVariantOption(UUID productId, String nameZh, String nombre) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO variant_option (id, product_id, name_zh, name, position) "
                + "VALUES (?, ?, ?, ?, 0)", id, productId, nameZh, nombre);
        return id;
    }

    private void insertVariantValue(UUID optionId, String valueZh, String valor, String imagenCdn, int position) {
        jdbcTemplate.update("INSERT INTO variant_value (id, option_id, value_zh, value, image_cdn_url, position) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)", optionId, valueZh, valor, imagenCdn, position);
    }

    private UUID productId(String slug) {
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE slug = ?", UUID.class, slug);
    }

    private int contarProductos(String externalId) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE external_id = ?",
                Integer.class, externalId);
        return n == null ? 0 : n;
    }

    private UUID idPorExternalId(String externalId) {
        return jdbcTemplate.queryForObject("SELECT id FROM product WHERE external_id = ?", UUID.class, externalId);
    }

    private ProductSeed newProduct(String slug, String tituloEs, String tituloEn, UUID categoryId) {
        return new ProductSeed(slug, tituloEs, tituloEn, categoryId);
    }

    /**
     * Constructor fluido de un producto de muestra. Existe para que cada test lea qué producto hay y con
     * qué valores, sin un constructor de quince argumentos posicionales donde nadie sabe cuál es cuál.
     */
    private final class ProductSeed {
        private final String slug;
        private final String tituloEs;
        private final String tituloEn;
        private final UUID categoryId;
        private String basePrice = "10.0000";
        private String rating = "4.00";
        private int monthlySales = 10;
        private String trendScore = "1";
        private String shipFrom = "CN";
        private boolean freeShipping;
        private boolean hasVideo;
        private int inventory = 100;
        private String status = "ACTIVE";
        private boolean mirroredImage = true;
        private Instant createdAt = Instant.now();

        private ProductSeed(String slug, String tituloEs, String tituloEn, UUID categoryId) {
            this.slug = slug;
            this.tituloEs = tituloEs;
            this.tituloEn = tituloEn;
            this.categoryId = categoryId;
        }

        private ProductSeed basePrice(String v) {
            this.basePrice = v;
            return this;
        }

        private ProductSeed rating(String v) {
            this.rating = v;
            return this;
        }

        private ProductSeed monthlySales(int v) {
            this.monthlySales = v;
            return this;
        }

        private ProductSeed trendScore(String v) {
            this.trendScore = v;
            return this;
        }

        private ProductSeed shipFrom(String v) {
            this.shipFrom = v;
            return this;
        }

        private ProductSeed freeShipping(boolean v) {
            this.freeShipping = v;
            return this;
        }

        private ProductSeed hasVideo(boolean v) {
            this.hasVideo = v;
            return this;
        }

        private ProductSeed inventory(int v) {
            this.inventory = v;
            return this;
        }

        private ProductSeed status(String v) {
            this.status = v;
            return this;
        }

        private ProductSeed mirroredImage(boolean v) {
            this.mirroredImage = v;
            return this;
        }

        private ProductSeed createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        private UUID insert() {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, category_id, "
                    + "title_zh, status, base_price, currency, rating, review_count, monthly_sales, trend_score, "
                    + "ship_from, free_shipping, self_pickup, has_video, inventory_count, moq, shipping_cny, "
                    + "iva_cny, created_at, updated_at, ingested_at) "
                    + "VALUES (?, ?, ?, '1688', ?, ?, ?, ?, ?, 'CNY', ?, 0, ?, ?, ?, ?, false, ?, ?, 1, 5, 1, "
                    + "?, ?, ?)",
                    id, slug, "EXT-" + slug, proveedor, categoryId, tituloEs, status, new BigDecimal(basePrice),
                    new BigDecimal(rating), monthlySales, new BigDecimal(trendScore), shipFrom, freeShipping,
                    hasVideo, inventory, Timestamp.from(createdAt), Timestamp.from(createdAt),
                    Timestamp.from(createdAt));
            insertImage(id, 0, "MAIN", mirroredImage ? IMG_CDN + slug + ".jpg" : null);
            jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                    + "VALUES (gen_random_uuid(), ?, 'es', ?, ?)", id, tituloEs, tituloEs + " — descripción");
            jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                    + "VALUES (gen_random_uuid(), ?, 'en', ?, ?)", id, tituloEn, tituloEn + " — description");
            return id;
        }
    }
}
