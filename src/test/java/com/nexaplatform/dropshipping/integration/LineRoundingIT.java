package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificación CALC-DIV: el importe que se cobra en la divisa del cliente, comprobado AL CÉNTIMO contra
 * cifras calculadas a mano.
 *
 * <p><b>El defecto que fija esta clase.</b> El importe en la divisa del cliente se obtenía convirtiendo
 * el precio UNITARIO, redondeándolo y multiplicándolo por la cantidad. Con un artículo de 0,15 $ y el
 * euro a 0,92, la unidad son 0,138 € que en pantalla se leen <b>0,14 €</b>; por cien unidades eso da
 * <b>14,00 €</b>, cuando lo que se compra son 15,00 $ = <b>13,80 €</b>. Veinte céntimos de más, un
 * <b>+1,45 % sistemático</b>, y la pasarela liquidaba esa cifra. Desde el 14-ago-2026 el importe de cada
 * línea se multiplica en dólares y se convierte UNA sola vez, al final ({@code OrderAmounts.lineSubtotal}).
 *
 * <p><b>La contrapartida.</b> Si el importe de la línea deja de ser «unitario × cantidad», el cliente no
 * puede cuadrar el subtotal multiplicando lo que ve. Por eso el importe de línea se PUBLICA junto al
 * unitario —«0,14 € /ud · 13,80 €»— y aquí se comprueba que sumando esos importes se llega exactamente
 * al subtotal.
 *
 * <p><b>DEFECTO VIVO (14-ago-2026).</b> Esa publicación llegó al carrito ({@code /api/catalog/cart-quote})
 * y a la ficha del pedido ({@code /api/me/orders/{id}}), pero NO a la vista previa del checkout: el
 * {@code QuoteResponse} de {@code ShippingQuoteController} no expone ni {@code items} ni
 * {@code subtotalFormatted}, aunque {@code CheckoutPreviewService} ya calcula las líneas
 * ({@code Preview.lines()}, con {@code unitFormatted} y {@code lineSubtotalFormatted}) y las documenta
 * como publicadas. Las dos pruebas que lo comprueban quedan {@code @Disabled} con el detalle exacto; el
 * IMPORTE que se cobra es correcto en las dos, lo que falta es el desglose que permite cuadrarlo.
 *
 * <p><b>Datos de partida</b> (sembrados en cada prueba, porque {@code BaseIntegration} vacía las tablas):
 * <ul>
 *   <li>USD = 1,00 y EUR = 0,92 en {@code currency_rate};</li>
 *   <li>producto en USD ({@code currency='USD'}) a 0,15 $ y SIN reglas de margen → el precio de venta es
 *       el coste, así que el número esperado se escribe a mano;</li>
 *   <li>zona de envío con porte plano de 5,00 $ y sin IVA ni aduanas configurados.</li>
 * </ul>
 */
class LineRoundingIT extends BaseIntegration {

    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String COTIZAR = "/api/shipping/quote";
    private static final String CARRITO = "/api/catalog/cart-quote";
    private static final String DIRECCIONES = "/api/me/addresses";
    private static final String MIS_PEDIDOS = "/api/me/orders";
    private static final String CABECERA_DIVISA = "X-Currency";

    private static final String PAIS = "ES";
    /** Porte plano, para que el envío no dependa del peso ni mueva la cuenta del subtotal. */
    private static final int ENVIO_CENTS = 500;
    /** El artículo del caso: 15 céntimos de dólar. */
    private static final String PRECIO_QUINCE_CENTIMOS = "0.1500";
    /** Euro por dólar. Todos los importes esperados de la clase salen de multiplicar por esta tasa. */
    private static final String EURO = "0.92";

    private static final long SALDO_HOLGADO = 50_000_000L;

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    @Autowired
    private MarginService marginService;
    @Autowired
    private CurrencyRateService divisas;

    private UUID userId;
    private String token;
    private UUID productId;
    private UUID direccionId;

    @BeforeEach
    void prepararEscenario() {
        // La caché de reglas de margen se calienta al arrancar el contexto, ANTES del TRUNCATE: sin
        // invalidarla la primera prueba tarificaría con el margen de los seeds.
        marginService.invalidateCache();
        sembrarDivisa("USD", "US Dollar", "$", "en-US", "1.00");
        sembrarDivisa("EUR", "Euro", "€", "es-ES", EURO);
        // La caché de divisas (TTL 5 min) también sobrevive al TRUNCATE; overrideRate es el único punto
        // público que la vuelve a leer de la tabla, y con USD = 1 la operación es neutra.
        divisas.overrideRate("USD", BigDecimal.ONE);

        userId = UUID.randomUUID();
        token = jwt.userToken(userId, "divisas@nx036.local", "USER");
        insertarUsuario(userId, "divisas@nx036.local");
        acreditarWallet(userId, SALDO_HOLGADO);
        insertarZona(PAIS, ENVIO_CENTS);
        productId = insertarProducto(PRECIO_QUINCE_CENTIMOS);
        direccionId = crearDireccion(PAIS);
    }

    /* ==================================================================================
     *  A · El caso exacto: 0,15 $ × 100 al cambio 0,92 son 13,80 €
     * ================================================================================== */

    @Test
    @DisplayName("100 unidades de 0,15 $ se cobran a 13,80 €, no a 14,00 €")
    void cienUnidadesDeQuinceCentimosSeCobranATreceOchenta() {
        JsonNode carrito = cuerpo(cotizarCarrito(100));

        // 0,15 × 0,92 = 0,138 → el unitario que ve el cliente es 0,14 €...
        assertThat(importe(carrito.get("items").get(0).get("unitFormatted").asText())).isEqualByComparingTo("0.14");
        // ...pero la línea son 100 × 0,15 = 15,00 $, y ESO convertido da 13,80 €.
        assertThat(importe(carrito.get("items").get(0).get("lineTotalFormatted").asText()))
                .as("0,14 € × 100 = 14,00 € era el cobro inflado: 20 céntimos de más, un +1,45 %")
                .isEqualByComparingTo("13.80");
        assertThat(importe(carrito.get("subtotalFormatted").asText())).isEqualByComparingTo("13.80");
    }

    @Test
    @DisplayName("la vista previa del checkout enseña el mismo importe de línea y el mismo subtotal")
    void laVistaPreviaEnsenaElMismoImporteDeLinea() {
        JsonNode previa = cuerpo(cotizar(100));

        assertThat(importe(previa.get("items").get(0).get("unitFormatted").asText())).isEqualByComparingTo("0.14");
        assertThat(importe(previa.get("items").get(0).get("lineTotalFormatted").asText()))
                .isEqualByComparingTo("13.80");
        assertThat(importe(previa.get("subtotalFormatted").asText())).isEqualByComparingTo("13.80");
        // 13,80 de producto + 4,60 de envío (5,00 $ × 0,92) = 18,40 €, sin IVA ni aduanas configurados.
        assertThat(importe(previa.get("shippingFormatted").asText())).isEqualByComparingTo("4.60");
        assertThat(importe(previa.get("totalFormatted").asText())).isEqualByComparingTo("18.40");
    }

    @Test
    @DisplayName("la ficha del pedido cobrado repite las mismas cifras que la vista previa")
    void laFichaDelPedidoRepiteLasCifrasDeLaVistaPrevia() {
        JsonNode previa = cuerpo(cotizar(100));
        JsonNode creado = cuerpo(checkout(100));

        JsonNode detalle = cuerpo(client.get().uri(MIS_PEDIDOS + "/" + creado.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).header(CABECERA_DIVISA, "EUR").exchange()
                .expectStatus().isOk());

        assertThat(importe(detalle.get("items").get(0).get("lineTotalFormatted").asText()))
                .isEqualByComparingTo("13.80");
        assertThat(importe(detalle.get("subtotalFormatted").asText())).isEqualByComparingTo("13.80");
        assertThat(importe(detalle.get("totalFormatted").asText()))
                .as("lo que se le enseñó antes de pagar es lo que dice su pedido, al céntimo")
                .isEqualByComparingTo(importe(previa.get("totalFormatted").asText()));
    }

    @Test
    @DisplayName("en dólares el pedido sigue costando 15,00 $: el canónico no se toca")
    void enDolaresElPedidoSigueCostandoQuinceDolares() {
        // La corrección es de CONVERSIÓN: en la moneda canónica el precio unitario ES el precio con dos
        // decimales, así que multiplicarlo por la cantidad ya era exacto y aquí no puede cambiar nada.
        JsonNode pedido = cuerpo(checkoutEnDolares(100));

        assertThat(centimos(pedido, "subtotal")).isEqualTo(1500);
        assertThat(centimos(pedido, "total")).isEqualTo(1500 + ENVIO_CENTS);
        // Y del monedero (que lleva dólares) sale exactamente el total canónico.
        assertThat(saldoEnBd()).isEqualTo(SALDO_HOLGADO - (1500 + ENVIO_CENTS));
    }

    /* ==================================================================================
     *  B · Los bordes: una unidad, medio céntimo y varias líneas
     * ================================================================================== */

    @Test
    @DisplayName("con una sola unidad no cambia nada: 0,15 $ siguen siendo 0,14 €")
    void conUnaSolaUnidadNoCambiaNada() {
        JsonNode carrito = cuerpo(cotizarCarrito(1));

        // Sin cantidad que multiplicar no hay redondeo que multiplicar: unitario e importe coinciden.
        assertThat(importe(carrito.get("items").get(0).get("unitFormatted").asText())).isEqualByComparingTo("0.14");
        assertThat(importe(carrito.get("items").get(0).get("lineTotalFormatted").asText()))
                .isEqualByComparingTo("0.14");
    }

    @Test
    @DisplayName("un importe de línea que cae en medio céntimo redondea hacia arriba")
    void unImporteQueCaeEnMedioCentimoRedondeaHaciaArriba() {
        // Con el euro a 0,875, tres unidades de 0,20 $ son 0,60 $ × 0,875 = 0,525 € EXACTOS. HALF_UP lo
        // sube a 0,53 €; truncar dejaría 0,52 y el cobro saldría un céntimo barato.
        sembrarDivisa("EUR", "Euro", "€", "es-ES", "0.875");
        divisas.overrideRate("USD", BigDecimal.ONE);
        productId = insertarProducto("0.2000");

        JsonNode carrito = cuerpo(cotizarCarrito(3));

        assertThat(importe(carrito.get("items").get(0).get("lineTotalFormatted").asText()))
                .as("0,525 € sube a 0,53, nunca baja a 0,52").isEqualByComparingTo("0.53");
    }

    @Test
    @DisplayName("con varias líneas distintas, la suma de los importes de línea ES el subtotal")
    void variasLineasDistintasSumanExactamenteElSubtotal() {
        // 0,15 $ × 100 = 15,00 $ × 0,92 = 13,80    → 13,80 €
        // 1,99 $ ×   3 =  5,97 $ × 0,92 =  5,4924  →  5,49 €
        // 7,05 $ ×   7 = 49,35 $ × 0,92 = 45,402   → 45,40 €
        // Subtotal = 64,69 €. Multiplicando unitarios redondeados saldría 64,92 €: 23 céntimos de más.
        UUID casiDosDolares = insertarProducto("1.9900");
        UUID sieteDolares = insertarProducto("7.0500");
        String cuerpo = """
                [{"productId":"%s","quantity":100},{"productId":"%s","quantity":3},
                 {"productId":"%s","quantity":7}]
                """.formatted(productId, casiDosDolares, sieteDolares);

        JsonNode carrito = cuerpo(client.post().uri(CARRITO).header(CABECERA_DIVISA, "EUR")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange().expectStatus().isOk());

        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode linea : carrito.get("items")) {
            suma = suma.add(importe(linea.get("lineTotalFormatted").asText()));
        }
        assertThat(suma).isEqualByComparingTo("64.69");
        assertThat(importe(carrito.get("subtotalFormatted").asText()))
                .as("sumando los importes que se enseñan se llega al subtotal, sin céntimos sueltos")
                .isEqualByComparingTo(suma);
        assertThat(suma).as("64,92 € era la suma de unitarios redondeados").isNotEqualByComparingTo("64.92");
    }

    @Test
    @DisplayName("con varias líneas, la vista previa y el pedido cobrado siguen coincidiendo al céntimo")
    void conVariasLineasLaVistaPreviaYElCobroCoinciden() {
        UUID casiDosDolares = insertarProducto("1.9900");
        String lineas = """
                {"productId":"%s","quantity":100},{"productId":"%s","quantity":3}
                """.formatted(productId, casiDosDolares);

        JsonNode previa = cuerpo(client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header(CABECERA_DIVISA, "EUR").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"country\":\"" + PAIS + "\",\"region\":null,\"items\":[" + lineas + "]}").exchange()
                .expectStatus().isOk());
        JsonNode creado = cuerpo(client.post().uri(CHECKOUT).header("Idempotency-Key", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).header(CABECERA_DIVISA, "EUR")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"shippingAddressId\":\"" + direccionId
                        + "\",\"paymentMethod\":\"WALLET\"," + "\"items\":[" + lineas + "]}")
                .exchange().expectStatus().isCreated());

        JsonNode detalle = cuerpo(client.get().uri(MIS_PEDIDOS + "/" + creado.get("id").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).header(CABECERA_DIVISA, "EUR").exchange()
                .expectStatus().isOk());

        // 13,80 + 5,49 = 19,29 € de producto, más 4,60 € de envío = 23,89 €.
        assertThat(importe(previa.get("subtotalFormatted").asText())).isEqualByComparingTo("19.29");
        assertThat(importe(detalle.get("subtotalFormatted").asText())).isEqualByComparingTo("19.29");
        assertThat(importe(detalle.get("totalFormatted").asText()))
                .isEqualByComparingTo(importe(previa.get("totalFormatted").asText()));
        assertThat(importe(detalle.get("totalFormatted").asText())).isEqualByComparingTo("23.89");
    }

    /* ==================================================================================
     *  Utilidades
     * ================================================================================== */

    private WebTestClient.ResponseSpec cotizarCarrito(int cantidad) {
        String cuerpo = "[{\"productId\":\"" + productId + "\",\"quantity\":" + cantidad + "}]";
        return client.post().uri(CARRITO).header(CABECERA_DIVISA, "EUR").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo).exchange().expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec cotizar(int cantidad) {
        String cuerpo = """
                {"country":"%s","region":null,"items":[{"productId":"%s","quantity":%d}]}
                """.formatted(PAIS, productId, cantidad);
        return client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header(CABECERA_DIVISA, "EUR").contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec checkout(int cantidad) {
        return checkout(cantidad, "EUR");
    }

    /** Sin cabecera de divisa: la respuesta del checkout viene en la moneda canónica (dólares). */
    private WebTestClient.ResponseSpec checkoutEnDolares(int cantidad) {
        return checkout(cantidad, null);
    }

    private WebTestClient.ResponseSpec checkout(int cantidad, String divisa) {
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\","
                + "\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + cantidad + "}]}";
        WebTestClient.RequestBodySpec peticion = client.post().uri(CHECKOUT)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON);
        if (divisa != null) {
            peticion = peticion.header(CABECERA_DIVISA, divisa);
        }
        return peticion.bodyValue(cuerpo).exchange().expectStatus().isCreated();
    }

    private JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        String texto = respuesta.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readTree(texto == null ? "{}" : texto);
        } catch (JacksonException e) {
            throw new IllegalStateException("respuesta no es JSON: " + texto, e);
        }
    }

    /**
     * Importe de una cadena YA formateada por el backend ("13,80 €"). El locale del euro es es-ES: punto
     * de miles y coma decimal. Se descarta todo lo que no sea cifra o separador —símbolo y espacio, que
     * según la versión de CLDR puede ser duro o fino— para que el caso mida el importe y no la máquina
     * virtual donde corre.
     */
    private static BigDecimal importe(String formateado) {
        String limpio = formateado.replaceAll("[^0-9,.-]", "").replace(".", "").replace(',', '.');
        return new BigDecimal(limpio);
    }

    private static int centimos(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        assertThat(valor).as("falta el campo monetario «%s» en la respuesta: %s", campo, nodo).isNotNull();
        return valor.decimalValue().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /* ---------- Siembra ---------- */

    private void sembrarDivisa(String codigo, String nombre, String simbolo, String locale, String tasa) {
        jdbcTemplate.update(
                "INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::numeric, TRUE) ON CONFLICT (code) DO UPDATE"
                        + " SET rate_vs_usd = EXCLUDED.rate_vs_usd, active = TRUE",
                codigo, nombre, simbolo, locale, tasa);
    }

    private void insertarUsuario(UUID id, String email) {
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())", id, email);
    }

    private void acreditarWallet(UUID id, long centimos) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", id, centimos);
    }

    private void insertarZona(String pais, int baseCents) {
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone,"
                + " base_cents, per_kg_cents, eta_min_days, eta_max_days, enabled)"
                + " VALUES (gen_random_uuid(), ?, ?, 'EU', ?, 0, 5, 10, true)", pais, pais, baseCents);
    }

    /** Producto tarifado en USD y sin regla de margen: el precio de venta ES el precio sembrado. */
    private UUID insertarProducto(String basePrice) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                + " base_price, currency, shipping_cny, iva_cny, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TEST', 'Producto de prueba', 'ACTIVE', 1, ?::numeric,"
                + " 'USD', 0, 0, 500, now(), now())", id, "producto-" + sufijo, "ext-" + sufijo, basePrice);
        return id;
    }

    private UUID crearDireccion(String pais) {
        String cuerpo = """
                {"fullName":"Comprador de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","postalCode":"28013","country":"%s","isDefault":true}
                """.formatted(pais);
        JsonNode creada = cuerpo(client.post().uri(DIRECCIONES).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange().expectStatus().isCreated());
        return UUID.fromString(creada.get("id").asText());
    }

    private long saldoEnBd() {
        Long saldo = jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?", Long.class,
                userId);
        return saldo == null ? 0L : saldo;
    }
}
