package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * El cliente elige la forma de envío y se le cobra ESA, de principio a fin y por HTTP.
 *
 * <p>Lo que aquí se comprueba y no puede comprobar ningún test unitario: que el canal elegido recorre
 * entero el camino —cotización → total → cobro → columna del pedido— sin perderse en ninguna de las
 * capas. El fallo que este fichero impide es el que más caro sale: enseñar el precio de un canal y
 * despachar por otro.
 *
 * <p>Se comprueba también <b>el impuesto</b>. El envío entra en la base imponible, así que elegir un
 * canal más caro no sube el total en la diferencia de porte sino en la diferencia MÁS su IVA: es la
 * razón por la que el selector recotiza contra el servidor en vez de sumar en el navegador.
 *
 * <p>El transportista se sustituye por un doble: la cotización real es una llamada a YunExpress con
 * credenciales de producción, y un test no puede depender de una red ajena ni —menos aún— gastar saldo
 * de una cuenta real. Lo que se prueba es NUESTRO camino desde que llegan las tarifas.
 */
class ShippingOptionCheckoutIT extends BaseIntegration {

    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String COTIZAR = "/api/shipping/quote";
    private static final String DIRECCIONES = "/api/me/addresses";

    private static final String PAIS = "ES";
    /** Precio de proveedor en USD y sin margen: el precio de venta ES el coste, 10,00 $. */
    private static final String PRECIO_BASE = "10.0000";
    private static final int UNIDAD_CENTS = 1000;
    private static final long SALDO_HOLGADO = 10_000_000L;

    /** La línea de ropa: más barata y más rápida. Es la que cotiza el servidor si no se elige nada. */
    private static final String CANAL_BARATO = "FZZXR";
    private static final int PORTE_BARATO = 785;
    /** La línea generalista: más cara y más lenta, pero sirve cualquier producto. */
    private static final String CANAL_CARO = "THPHR";
    private static final int PORTE_CARO = 821;

    private static final List<ShippingOption> COTIZADAS = List.of(
            new ShippingOption(CANAL_BARATO, "Apparel line", PORTE_BARATO, 5, 8),
            new ShippingOption(CANAL_CARO, "Global line", PORTE_CARO, 6, 10));

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    /** El transportista, sustituido: ver la nota de la clase. */
    @MockitoBean
    private FulfillmentProvider fulfillment;

    @Autowired
    private MarginService marginService;

    private UUID userId;
    private String token;
    private UUID productId;
    private UUID direccionId;

    @BeforeEach
    void prepararEscenario() {
        marginService.invalidateCache();
        userId = UUID.randomUUID();
        token = jwt.userToken(userId, "envio@nx036.local", "USER");
        insertarUsuario(userId, "envio@nx036.local");
        acreditarWallet(userId, SALDO_HOLGADO);
        productId = insertarProducto(PRECIO_BASE);
        direccionId = crearDireccion(PAIS);
        // La cotización del transportista: dos canales, ordenados de más barato a más caro, y
        // `amountUsdCents` = el del más barato, que es lo que devuelve el servicio real.
        when(fulfillment.quote(anyString(), any())).thenReturn(new ShippingQuote(true, PAIS, PORTE_BARATO,
                "Standard Shipping", "Standard Shipping", 5, 8, "EU", COTIZADAS));
        // El checkout rechaza el destino que el transportista no cubre, antes incluso de cotizarlo.
        when(fulfillment.isSupported(anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("la cotización publica las dos formas de envío y dice con cuál está calculada")
    void laCotizacionPublicaLasOpciones() {
        JsonNode previa = cotizar(null);

        assertThat(previa.get("options")).hasSize(2);
        assertThat(previa.get("options").get(0).get("code").asText()).isEqualTo(CANAL_BARATO);
        assertThat(previa.get("options").get(0).get("etaMinDays").asInt()).isEqualTo(5);
        assertThat(previa.get("options").get(1).get("code").asText()).isEqualTo(CANAL_CARO);
        assertThat(previa.get("selectedShippingOptionCode").asText()).as("sin elegir nada se cotiza el más barato")
                .isEqualTo(CANAL_BARATO);
        assertThat(previa.get("amountUsdCents").asInt()).isEqualTo(PORTE_BARATO);
    }

    @Test
    @DisplayName("elegir el canal caro sube el envío de la cotización a SU tarifa")
    void elegirUnCanalRecotizaElEnvio() {
        JsonNode previa = cotizar(CANAL_CARO);

        assertThat(previa.get("amountUsdCents").asInt()).isEqualTo(PORTE_CARO);
        assertThat(previa.get("selectedShippingOptionCode").asText()).isEqualTo(CANAL_CARO);
    }

    @Test
    @DisplayName("el impuesto se recalcula sobre el envío elegido, no sobre el más barato")
    void elImpuestoSigueAlEnvioElegido() {
        // La razón de que el selector recotice contra el servidor: con el 21% de IVA, elegir el canal
        // caro no cuesta 0,36 $ más, sino 0,36 $ más su impuesto. Sumar la diferencia en el navegador
        // dejaría el total corto y el cobro no cuadraría con lo enseñado.
        insertarIva(PAIS, 2100);

        JsonNode barata = cotizar(CANAL_BARATO);
        JsonNode cara = cotizar(CANAL_CARO);

        int ivaBarato = centimosDeTexto(barata.get("taxFormatted").asText());
        int ivaCaro = centimosDeTexto(cara.get("taxFormatted").asText());
        assertThat(ivaBarato).isEqualTo(ivaDe(UNIDAD_CENTS + PORTE_BARATO));
        assertThat(ivaCaro).isEqualTo(ivaDe(UNIDAD_CENTS + PORTE_CARO));
        assertThat(centimosDeTexto(cara.get("totalFormatted").asText())
                - centimosDeTexto(barata.get("totalFormatted").asText()))
                .as("la diferencia de total es la del porte MÁS su impuesto")
                .isEqualTo(PORTE_CARO - PORTE_BARATO + (ivaCaro - ivaBarato));
    }

    @Test
    @DisplayName("el pedido se cobra por el canal elegido y lo guarda para emitir la guía por él")
    void elPedidoGuardaElCanalElegido() {
        JsonNode pedido = checkout(CANAL_CARO);

        assertThat(centimos(pedido, "shipping")).isEqualTo(PORTE_CARO);
        assertThat(centimos(pedido, "total")).isEqualTo(UNIDAD_CENTS + PORTE_CARO);
        assertThat(canalGuardado()).as("sin la columna, la guía saldría por un canal distinto del cotizado")
                .isEqualTo(CANAL_CARO);
    }

    @Test
    @DisplayName("un canal que no cotiza no abarata el pedido: se cobra el más barato de los que sí")
    void unCanalInventadoNoAbarataElPedido() {
        // El intento evidente: mandar el código de un postal barato —fuera del IVA prepagado, que rompe
        // el DDP— para pagar menos porte del que costará despachar.
        JsonNode previa = cotizar("CNDWA");
        assertThat(previa.get("amountUsdCents").asInt()).isEqualTo(PORTE_BARATO);
        assertThat(previa.get("selectedShippingOptionCode").asText()).isEqualTo(CANAL_BARATO);

        JsonNode pedido = checkout("CNDWA");

        assertThat(centimos(pedido, "shipping")).isEqualTo(PORTE_BARATO);
        assertThat(canalGuardado()).isEqualTo(CANAL_BARATO);
    }

    @Test
    @DisplayName("sin elegir forma de envío se cobra la más barata, y es la que queda guardada")
    void sinElegirSeCobraLaMasBarata() {
        JsonNode pedido = checkout(null);

        assertThat(centimos(pedido, "shipping")).isEqualTo(PORTE_BARATO);
        assertThat(canalGuardado()).isEqualTo(CANAL_BARATO);
    }

    @Test
    @DisplayName("lo que se enseña antes de pagar es lo que se cobra, también al elegir canal")
    void laVistaPreviaYElCobroCoincidenAlCentimo() {
        insertarIva(PAIS, 2100);

        JsonNode previa = cotizar(CANAL_CARO);
        JsonNode pedido = checkout(CANAL_CARO);

        assertThat(previa.get("amountUsdCents").asInt()).isEqualTo(centimos(pedido, "shipping"));
        assertThat(centimosDeTexto(previa.get("taxFormatted").asText())).isEqualTo(centimos(pedido, "tax"));
        assertThat(centimosDeTexto(previa.get("totalFormatted").asText())).isEqualTo(centimos(pedido, "total"));
    }

    /** El 21% de una base, al céntimo y redondeando como el sistema (mitad hacia arriba). */
    private static int ivaDe(int baseCents) {
        return java.math.BigDecimal.valueOf(baseCents).multiply(java.math.BigDecimal.valueOf(2100))
                .divide(java.math.BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).intValueExact();
    }

    /* ---------- Peticiones ---------- */

    private WebTestClient.ResponseSpec cotizarSpec(String canal) {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":null,"shippingOptionCode":%s,
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(PAIS, canal == null ? "null" : "\"" + canal + "\"", productId);
        return client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange();
    }

    private JsonNode cotizar(String canal) {
        return cuerpo(cotizarSpec(canal).expectStatus().isOk());
    }

    private JsonNode checkout(String canal) {
        String canalJson = canal == null ? "" : ",\"shippingOptionCode\":\"" + canal + "\"";
        String cuerpo = "{\"shippingAddressId\":\"" + direccionId + "\",\"paymentMethod\":\"WALLET\"" + canalJson
                + ",\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}";
        return cuerpo(client.post().uri(CHECKOUT).header(HttpHeaders.AUTHORIZATION, bearer(token))
                // La clave de idempotencia es OBLIGATORIA en todo lo que mueve dinero: sin ella el
                // servidor responde 400. Un arnés de prueba es un cliente más y tiene que mandarla.
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cuerpo).exchange().expectStatus().isCreated());
    }

    /* ---------- Lecturas y siembra ---------- */

    private String canalGuardado() {
        return jdbcTemplate.queryForObject("SELECT shipping_channel_code FROM customer_order WHERE user_id = ?",
                String.class, userId);
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

    private UUID insertarProducto(String basePrice) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        // La partida 6109 (camisetas de punto) no es decorativa: desde que hay dos transportistas, el
        // enrutador solo ofrece la línea de ropa —la más barata de las dos de esta prueba— si TODO el
        // pedido es textil. Un producto sin partida perdería esa opción y aquí se cotizaría la cara.
        jdbcTemplate.update(
                "INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                        + " base_price, currency, shipping_cny, iva_cny, weight_grams, hs_code, created_at,"
                        + " updated_at)" + " VALUES (?, ?, ?, 'TEST', 'Producto de prueba', 'ACTIVE', 1, ?::numeric,"
                        + " 'USD', 0, 0, 500, '610910', now(), now())",
                id, "producto-" + sufijo, "ext-" + sufijo, basePrice);
        return id;
    }

    private void insertarIva(String pais, int rateBps) {
        jdbcTemplate.update(
                "INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                        + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                pais, rateBps);
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

    /* ---------- Utilidades ---------- */

    private JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        String texto = respuesta.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readTree(texto == null ? "{}" : texto);
        } catch (JacksonException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + texto, e);
        }
    }

    private static int centimos(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        assertThat(valor).as("falta el campo monetario «%s» en la respuesta: %s", campo, nodo).isNotNull();
        return valor.decimalValue().movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Céntimos de un importe ya formateado por el backend ("$17.85"). */
    private static int centimosDeTexto(String formateado) {
        String limpio = formateado.replace(",", "").replaceAll("[^0-9.\\-]", "");
        return new java.math.BigDecimal(limpio).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
