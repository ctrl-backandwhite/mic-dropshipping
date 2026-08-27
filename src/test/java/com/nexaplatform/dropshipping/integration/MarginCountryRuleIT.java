package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.pricing.PricingCountryFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Margen POR PAÍS de punta a punta: reglas reales en {@code price_rule}, petición HTTP con la cabecera de
 * país que manda el escaparate y el precio publicado que sale por el otro lado.
 *
 * <p><b>Por qué hace falta una prueba de integración y no basta la unitaria.</b> {@code MarginService} ya
 * tiene su caso con dobles, pero ahí el país se pone a mano en el ThreadLocal. En producción la cadena tiene
 * cuatro eslabones y cualquiera de ellos rompe el cobro sin que la unitaria se entere: el filtro
 * {@link PricingCountryFilter} lee la cabecera, {@code PricingCountryHolder} la normaliza, la caché de la
 * ficha incluye —o no— el país en su clave, y la caché de reglas del margen tarda cinco minutos en
 * refrescarse. Un fallo en el eslabón de la caché es especialmente traicionero: no da error, sirve el precio
 * de OTRO país durante el TTL.
 *
 * <p><b>El escenario es el de producción.</b> Hoy hay 27 reglas GLOBAL/STOREFRONT —una por país de la UE— al
 * 104 % (el 100 % base más lo que cubre la comisión del 2 % que YunExpress cobra por adelantar el IVA) y una
 * regla GLOBAL sin país al 120 % para el resto del mundo. Ver la migración {@code schema-v107-eu-margin-seed}.
 *
 * <p><b>Los números.</b> El producto de la muestra cuesta 72,40 CNY = 10,00 $ exactos (tasa 7,24) y no lleva
 * IVA ni envío, para que el precio publicado sea SOLO el margen y cualquier desviación se lea de un vistazo:
 * <ul>
 *   <li>global 120 % → 10,00 × 2,20 = <b>22,00 $</b> (resto del mundo);</li>
 *   <li>España 104 % → 10,00 × 2,04 = <b>20,40 $</b> (UE).</li>
 * </ul>
 */
class MarginCountryRuleIT extends BaseIntegration {

    /** 72,40 CNY ÷ 7,24 = 10,00 $ de coste. Todos los importes de la clase derivan de aquí. */
    private static final BigDecimal BASE_CNY = new BigDecimal("72.40");

    /** Regla GLOBAL sin país: 120 % → 22,00 $. Es la que cobra todo país que no tenga la suya. */
    private static final String PRECIO_RESTO_DEL_MUNDO = "$22.00";
    /** Regla GLOBAL con país: 104 % → 20,40 $. Es la que cobran los 27 de la UE. */
    private static final String PRECIO_UE = "$20.40";

    private static final String FICHA = "/api/catalog/products/by-id/{id}?lang=es";
    private static final String LISTADO = "/api/catalog/products?size=50";
    /** Cabecera de país por IP que inyecta Cloudflare cuando el front no manda país (comprador invitado). */
    private static final String CABECERA_CDN = "CF-IPCountry";

    /** Los 27 de la UE, tal cual los siembra la migración v107. */
    private static final List<String> UE_27 = List.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
            "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");

    @Autowired
    private MarginService margenes;
    @Autowired
    private CurrencyRateService divisas;

    private UUID producto;

    @BeforeEach
    void sembrarDivisasReglasYProducto() {
        sembrarDivisa("USD", "US Dollar", "$", "en-US", "1.00");
        sembrarDivisa("CNY", "Chinese Yuan", "¥", "zh-CN", "7.24");
        // La caché de tasas (TTL 5 min) sobrevive al TRUNCATE de BaseIntegration; `overrideRate` es el único
        // punto público que la vuelve a leer de la tabla, y con USD = 1 la operación es neutra.
        divisas.overrideRate("USD", BigDecimal.ONE);

        sembrarReglaGlobal(null, "120.00");
        sembrarReglaGlobal("ES", "104.00");
        // El ajuste MOQ es una fila única que el TRUNCATE borra, pero el servicio conserva en memoria el
        // valor que dejó otra clase de prueba. Se repone el de producción para que el producto de la
        // muestra (MOQ 1, que no lo activa) no dependa de quién corrió antes.
        jdbcTemplate.update("INSERT INTO moq_margin_setting (id, enabled, factor_percent) VALUES (1, TRUE, 50) "
                + "ON CONFLICT (id) DO UPDATE SET enabled = TRUE, factor_percent = 50");
        margenes.invalidateCache();

        producto = sembrarProducto("camiseta-margen-por-pais");
    }

    /* ==================================================================================
     * 1. Precedencia: la regla del país gana; el resto del mundo cae a la global
     * ================================================================================== */

    @Test
    @DisplayName("Un país CON regla propia cobra su margen: España al 104 % → 20,40 $")
    void elPaisConReglaPropiaCobraSuMargen() {
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "ES", PRECIO_UE);
    }

    @Test
    @DisplayName("Un país SIN regla propia cae a la global: México al 120 % → 22,00 $")
    void unPaisSinReglaPropiaCaeALaGlobal() {
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "MX", PRECIO_RESTO_DEL_MUNDO);
    }

    @Test
    @DisplayName("Sin cabecera de país se aplica la regla global: 22,00 $")
    void sinCabeceraDePaisSeAplicaLaGlobal() {
        // Es el caso del robot de indexación y del cliente que llega sin proxy geolocalizador: nunca puede
        // quedarse sin precio ni caer en la regla de un país que no es el suyo.
        client.get().uri(FICHA, producto).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayFormatted").isEqualTo(PRECIO_RESTO_DEL_MUNDO);
    }

    @Test
    @DisplayName("El código de país en minúsculas se normaliza y aplica igual: 'es' → 20,40 $")
    void elPaisEnMinusculasSeNormaliza() {
        // El front compone la cabecera a partir del país de registro del usuario, y ese dato ha entrado por
        // formularios y por proveedores de identidad distintos: llega en ambas cajas. Si la comparación
        // fuera sensible a mayúsculas, media UE pagaría el margen del resto del mundo sin que nadie lo viera.
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "es", PRECIO_UE);
    }

    @Test
    @DisplayName("Una regla de país DESACTIVADA no gana: España vuelve a los 22,00 $ de la global")
    void unaReglaDePaisDesactivadaNoGanaALaGlobal() {
        jdbcTemplate.update("UPDATE price_rule SET active = FALSE WHERE country_code = 'ES'");
        refrescarReglas();

        // Apagar la regla en el panel tiene que devolver el país a la global, no dejarlo sin margen (el
        // producto se vendería al coste) ni seguir cobrando el 104 % desde la caché.
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "ES", PRECIO_RESTO_DEL_MUNDO);
    }

    /* ==================================================================================
     * 2. De dónde sale el país: cabecera del front, cabecera del CDN y prioridad entre ambas
     * ================================================================================== */

    @Test
    @DisplayName("El país por IP del CDN también decide el margen del comprador invitado")
    void elPaisPorIpDelCdnDecideElMargenDelInvitado() {
        // Un invitado no tiene país de registro: el único dato es el que pone Cloudflare delante.
        precioDeLaFichaCon(CABECERA_CDN, "ES", PRECIO_UE);
    }

    @Test
    @DisplayName("Un país desconocido del CDN ('XX') se ignora y cae a la global")
    void elPaisDesconocidoDelCdnCaeALaGlobal() {
        // Cloudflare manda "XX" cuando no sabe de dónde viene la petición. Tomarlo por un país haría que la
        // resolución dependiera de que nadie diera de alta una regla con ese código.
        precioDeLaFichaCon(CABECERA_CDN, "XX", PRECIO_RESTO_DEL_MUNDO);
    }

    @Test
    @DisplayName("El país del front manda sobre el del CDN: usuario mexicano conectado desde España → 22,00 $")
    void elPaisDelFrontMandaSobreElDelCdn() {
        // Manda a dónde se ENVÍA (país de registro), no desde dónde se navega: el margen de la UE cubre el
        // prepago del IVA europeo, y un envío a México no lo paga por mucho que el cliente esté de viaje.
        client.get().uri(FICHA, producto)
                .header(PricingCountryFilter.HEADER_COUNTRY, "MX")
                .header(CABECERA_CDN, "ES")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.displayFormatted").isEqualTo(PRECIO_RESTO_DEL_MUNDO);
    }

    /* ==================================================================================
     * 3. La caché de la ficha no puede mezclar países
     * ================================================================================== */

    @Test
    @DisplayName("El precio cacheado de un país no se sirve a otro")
    void elPrecioCacheadoDeUnPaisNoSeSirveAOtro() {
        // La ficha se cachea cinco minutos. Si el país no formara parte de la clave, el primero en pedirla
        // fijaría el precio de todos los demás durante ese rato: un fallo silencioso, sin error ni traza, que
        // cobra de menos a media Europa o de más al resto del mundo según quién entre primero.
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "ES", PRECIO_UE);
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "MX", PRECIO_RESTO_DEL_MUNDO);
        // Y de vuelta: la entrada cacheada de España sigue siendo la suya.
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "ES", PRECIO_UE);
    }

    /* ==================================================================================
     * 4. El escenario de producción: 27 reglas de la UE conviviendo con la global
     * ================================================================================== */

    @Test
    @DisplayName("Con las 27 reglas de la UE, cada país europeo cobra 20,40 $ y el resto del mundo 22,00 $")
    void las27ReglasDeLaUeConvivenConLaGlobal() {
        sembrarUe27();

        // Con 28 reglas GLOBAL activas a la vez, la del país tiene que ganar SIEMPRE: si el desempate se
        // decidiera por posición o por orden de la base, el margen de un país saldría del de otro.
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "DE", PRECIO_UE);
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "PT", PRECIO_UE);
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "SE", PRECIO_UE);
        // Fuera de la UE, incluidos los vecinos que se confunden con ella (Reino Unido, Suiza).
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "GB", PRECIO_RESTO_DEL_MUNDO);
        precioDeLaFichaCon(PricingCountryFilter.HEADER_COUNTRY, "US", PRECIO_RESTO_DEL_MUNDO);
    }

    /* ==================================================================================
     * 5. El listado del escaparate tarifica igual que la ficha
     * ================================================================================== */

    @Test
    @DisplayName("El listado del escaparate también aplica el margen del país")
    void elListadoDelEscaparateTambienAplicaElMargenDelPais() {
        String token = bearer(jwt.userToken("USER"));

        // El listado y la ficha se tarifican y se cachean por caminos distintos: si solo uno mirara el país,
        // el cliente vería un precio en la parrilla y otro al abrir el producto.
        precioDelListado(token, "ES", PRECIO_UE);
        precioDelListado(token, "MX", PRECIO_RESTO_DEL_MUNDO);
    }

    /* ==================================================================================
     * Utilidades
     * ================================================================================== */

    /** Pide la ficha con una cabecera de país y comprueba el precio publicado. */
    private void precioDeLaFichaCon(String cabecera, String pais, String precioEsperado) {
        client.get().uri(FICHA, producto).header(cabecera, pais).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.displayFormatted").isEqualTo(precioEsperado);
    }

    /** Ídem sobre el listado del escaparate, que exige usuario autenticado. */
    private void precioDelListado(String token, String pais, String precioEsperado) {
        WebTestClient.BodyContentSpec body = client.get().uri(LISTADO)
                .header("Authorization", token)
                .header(PricingCountryFilter.HEADER_COUNTRY, pais)
                .exchange().expectStatus().isOk().expectBody();
        body.jsonPath("$.totalElements").isEqualTo(1);
        body.jsonPath("$.items[0].displayFormatted").isEqualTo(precioEsperado);
    }

    /**
     * Un cambio en las reglas solo se ve si se tiran las DOS cachés: la de reglas del margen (TTL 5 min) y
     * la de la ficha/listado. Es exactamente lo que hace el panel de admin al guardar una regla.
     */
    private void refrescarReglas() {
        margenes.invalidateCache();
        vaciarCaches();
    }

    /** Regla GLOBAL del canal del escaparate; {@code pais} nulo = vale para cualquier país. */
    private void sembrarReglaGlobal(String pais, String porcentaje) {
        jdbcTemplate.update("INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, "
                + "position, channel, country_code, description) VALUES (gen_random_uuid(), 'GLOBAL', NULL, "
                + "'PERCENTAGE', ?, TRUE, 0, 'STOREFRONT', ?, ?)",
                new BigDecimal(porcentaje), pais, pais == null ? "margen global" : "margen " + pais);
    }

    /** Las 26 reglas de la UE que faltan (España ya está sembrada), como en la migración v107. */
    private void sembrarUe27() {
        for (String pais : UE_27) {
            if (!"ES".equals(pais)) {
                sembrarReglaGlobal(pais, "104.00");
            }
        }
        refrescarReglas();
    }

    /**
     * Producto sin IVA ni envío: así el precio publicado es exclusivamente coste × margen y el número que se
     * afirma no arrastra otros sumandos. Lleva imagen espejada porque el listado del escaparate solo enseña
     * productos ACTIVE con {@code cdn_url}.
     */
    private UUID sembrarProducto(String slug) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, moq, base_price, "
                + "currency, iva_cny, shipping_cny, status, hs_code, weight_grams, inventory_count) "
                + "VALUES (?, ?, ?, '1688', ?, 1, ?, 'CNY', 0, 0, 'ACTIVE', '610910', 500, 100)",
                id, slug, "ext-" + slug, "测试商品 " + slug, BASE_CNY);
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', ?, ?)", id,
                "https://origen.test/" + slug + ".jpg", "https://cdn.nx036.test/img/" + slug + ".jpg");
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                + "VALUES (gen_random_uuid(), ?, 'es', ?, ?)", id, "Camiseta de muestra", "Descripción");
        return id;
    }

    private void sembrarDivisa(String codigo, String nombre, String simbolo, String locale, String tasa) {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, TRUE) ON CONFLICT (code) DO UPDATE "
                + "SET rate_vs_usd = EXCLUDED.rate_vs_usd, active = TRUE",
                codigo, nombre, simbolo, locale, new BigDecimal(tasa));
    }
}
