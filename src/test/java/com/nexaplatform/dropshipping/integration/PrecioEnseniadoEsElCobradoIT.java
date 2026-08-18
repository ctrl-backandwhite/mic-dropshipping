package com.nexaplatform.dropshipping.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Lo que se enseña antes de pagar es lo que se cobra — en CUALQUIER divisa y viva donde viva el comprador.
 *
 * <p>Nace de un descuadre real encontrado certificando en local el 18-ago-2026: el mismo carrito
 * enseñaba 23,19 $ y cobraba 22,66 $. El margen se resuelve por el país del comprador (cabecera
 * {@code X-Country}, su país de registro) y el cobro lo pisaba con el país de la dirección de ENVÍO, así
 * que en cuanto los dos se separaban —alguien registrado en México que manda el paquete a España— el
 * cargo dejaba de coincidir con lo enseñado.
 *
 * <p>Las 4.448 pruebas que había pasaban porque <b>todas cotizaban y cobraban con el mismo país</b>, y
 * porque el test que sí recorre las divisas ({@code CurrencyConversionIT}) llega hasta la cotización pero
 * nunca compra. Aquí se cierran los dos huecos a la vez: el país del comprador es distinto del destino, y
 * cada caso se comprueba en una divisa con decimales y en otra sin ellos.
 *
 * <p><b>Divisas elegidas.</b> No son decorativas: el yen y el peso chileno NO tienen céntimos, así que
 * cualquier redondeo a dos decimales que se cuele se ve; el peso mexicano con tasa 3 produce decimales
 * periódicos al dividir; la corona sueca pinta el símbolo detrás. Si el cargo se calculase con otro
 * redondeo que la vista previa, estas cuatro lo delatan antes que el dólar.
 */
@DisplayName("Certificación de importes · lo enseñado es lo cobrado, en toda divisa")
class PrecioEnseniadoEsElCobradoIT extends BaseIntegration {

    /** Destino del paquete: fija el IVA y el arancel, nunca el margen. */
    private static final String DESTINO = "ES";
    /** Dónde se registró el comprador: fija el MARGEN, aunque envíe a otro sitio. */
    private static final String PAIS_DEL_COMPRADOR = "MX";

    private static final int IVA_BPS = 2100;
    private static final int PORTE_CENTS = 785;
    private static final String PRECIO_BASE = "20.0000";
    private static final long SALDO_INICIAL_CENTS = 5_000_000L;

    /** Margen del país del comprador (México no tiene regla propia: cae a la global). */
    private static final String MARGEN_GLOBAL = "120.0000";
    /** Margen del DESTINO. Distinto a propósito: si el cobro lo usara, los importes no cuadrarían. */
    private static final String MARGEN_DESTINO = "104.0000";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @MockitoBean
    private FulfillmentProvider fulfillment;

    @Autowired
    private MarginService marginService;
    @Autowired
    private CurrencyRateService currencyRateService;

    private UUID clienteId;
    private String tokenCliente;
    private UUID productoId;
    private UUID direccionId;

    @BeforeEach
    void escenario() {
        vaciarCaches();
        sembrarDivisas();
        sembrarMargen(null, MARGEN_GLOBAL);
        sembrarMargen(DESTINO, MARGEN_DESTINO);
        marginService.invalidateCache();

        clienteId = UUID.randomUUID();
        tokenCliente = jwt.userToken(clienteId, "divisas-" + clienteId + "@nx036.local", "USER");
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())",
                clienteId, "divisas-" + clienteId + "@nx036.local");
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())",
                clienteId, SALDO_INICIAL_CENTS);
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                DESTINO, IVA_BPS);
        productoId = insertarProducto();
        direccionId = crearDireccion();

        when(fulfillment.isSupported(anyString())).thenReturn(true);
        when(fulfillment.quote(anyString(), any())).thenReturn(new ShippingQuote(true, DESTINO, PORTE_CENTS,
                "Standard Shipping", "Standard Shipping", 5, 8, "EU"));
    }

    /* ================================================================================== */

    @ParameterizedTest(name = "{0} ({1})")
    @CsvSource({
            "USD, con céntimos",
            "EUR, con céntimos",
            "SEK, con céntimos y el símbolo detrás",
            "MXN, con decimales periódicos al convertir",
            "JPY, SIN céntimos",
            "CLP, SIN céntimos",
    })
    @DisplayName("el total cobrado es, al céntimo, el que se enseñó en la vista previa")
    void loCotizadoEsLoCobrado(String divisa, String porQue) {
        JsonNode previa = cotizar(divisa);
        JsonNode pedido = comprar(divisa);

        assertThat(pedido.get("totalFormatted").asText())
                .as("divisa %s (%s): lo enseñado y lo cobrado tienen que ser el MISMO texto", divisa, porQue)
                .isEqualTo(previa.get("totalFormatted").asText());
        assertThat(pedido.get("subtotalFormatted").asText()).isEqualTo(previa.get("subtotalFormatted").asText());
        assertThat(pedido.get("taxFormatted").asText()).isEqualTo(previa.get("taxFormatted").asText());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "USD", "EUR", "SEK", "MXN", "JPY", "CLP" })
    @DisplayName("el desglose suma el total: subtotal + envío + impuesto, sin un céntimo suelto")
    void elDesgloseCuadra(String divisa) {
        JsonNode previa = cotizar(divisa);

        BigDecimal subtotal = importe(previa, "subtotalFormatted", divisa);
        BigDecimal envio = importe(previa, "shippingFormatted", divisa);
        BigDecimal impuesto = importe(previa, "taxFormatted", divisa);
        BigDecimal total = importe(previa, "totalFormatted", divisa);

        assertThat(subtotal.add(envio).add(impuesto))
                .as("en %s el cliente suma lo que ve; si no da el total, la pantalla miente", divisa)
                .isEqualByComparingTo(total);
    }

    @Test
    @DisplayName("el margen lo pone el país del COMPRADOR y el impuesto el del DESTINO")
    void elMargenEsDelCompradorYElImpuestoDelDestino() {
        // El comprador es mexicano (margen global 120 %) y envía a España (IVA 21 %). Coste 20 $ → con
        // el 120 % son 44,00 $ la unidad. Si el margen saliera del destino serían 40,80 $.
        JsonNode pedido = comprar("USD");

        assertThat(pedido.get("subtotalFormatted").asText())
                .as("con el margen del destino (104 %) saldrían 40,80 $: el país del comprador manda")
                .isEqualTo("$44.00");
        // El IVA español grava el producto MÁS el porte: 21 % de (44,00 + 7,85) = 10,8885 → 10,89.
        assertThat(pedido.get("taxFormatted").asText())
                .as("el IVA es el español, porque el paquete entra en España venga de donde venga quien compra")
                .isEqualTo("$10.89");
    }

    @Test
    @DisplayName("un comprador del propio destino paga su margen, y también cuadra")
    void elCompradorDelDestinoPagaElMargenDelDestino() {
        JsonNode previa = cotizarComo("USD", DESTINO);
        JsonNode pedido = comprarComo("USD", DESTINO);

        assertThat(previa.get("subtotalFormatted").asText()).isEqualTo("$40.80");
        assertThat(pedido.get("subtotalFormatted").asText())
                .as("previa y cobro siguen coincidiendo cuando los dos países SÍ son el mismo")
                .isEqualTo(previa.get("subtotalFormatted").asText());
    }

    /* ==================================================================================
     *  Peticiones
     * ================================================================================== */

    private JsonNode cotizar(String divisa) {
        return cotizarComo(divisa, PAIS_DEL_COMPRADOR);
    }

    private JsonNode cotizarComo(String divisa, String paisDelComprador) {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":null,"shippingOptionCode":null,
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(DESTINO, productoId);
        return cuerpo(peticion("/api/shipping/quote", divisa, paisDelComprador)
                .bodyValue(cuerpo).exchange().expectStatus().isOk());
    }

    private JsonNode comprar(String divisa) {
        return comprarComo(divisa, PAIS_DEL_COMPRADOR);
    }

    private JsonNode comprarComo(String divisa, String paisDelComprador) {
        String cuerpo = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(direccionId, productoId);
        return cuerpo(peticion("/api/me/orders/checkout", divisa, paisDelComprador)
                .bodyValue(cuerpo).exchange().expectStatus().isCreated());
    }

    private WebTestClient.RequestBodySpec peticion(String uri, String divisa, String paisDelComprador) {
        return client.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .header("X-Currency", divisa)
                .header("X-Country", paisDelComprador)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /* ==================================================================================
     *  Utilidades
     * ================================================================================== */

    private static JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        try {
            return JSON.readTree(new String(respuesta.expectBody().returnResult().getResponseBody()));
        } catch (Exception e) {
            throw new IllegalStateException("respuesta ilegible", e);
        }
    }

    /**
     * Del texto formateado al número. Se compara sobre el TEXTO que viaja al navegador —no sobre los
     * céntimos canónicos— porque es lo que el cliente tiene delante y lo que suma con el dedo.
     */
    private static BigDecimal importe(JsonNode nodo, String campo, String divisa) {
        String texto = nodo.get(campo).asText()
                .replace(" ", "").replace(" ", "").replace("‏", "")
                .replaceAll("[^0-9,.-]", "");
        boolean comaEsDecimal = texto.lastIndexOf(',') > texto.lastIndexOf('.');
        String normalizado = comaEsDecimal
                ? texto.replace(".", "").replace(',', '.')
                : texto.replace(",", "");
        return new BigDecimal(normalizado.isEmpty() ? "0" : normalizado);
    }

    private UUID insertarProducto() {
        UUID proveedorId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, created_at, updated_at)"
                + " VALUES (?, ?, '1688', 'Proveedor de prueba', now(), now())",
                proveedorId, "sup-" + proveedorId);
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, title_zh,"
                + " status, moq, base_price, currency, hs_code, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, '1688', ?, '棉质T恤', 'ACTIVE', 1, ?::numeric, 'USD', '610910', 500,"
                + " now(), now())",
                id, "producto-" + sufijo, "ext-" + sufijo, proveedorId, PRECIO_BASE);
        for (String[] t : new String[][] { { "es", "Camiseta de algodón" }, { "en", "Cotton T-Shirt" },
                { "zh", "棉质T恤" } }) {
            jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title,"
                    + " created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, now(), now())",
                    id, t[0], t[1]);
        }
        return id;
    }

    private UUID crearDireccion() {
        String cuerpo = """
                {"fullName":"Comprador de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","state":"M","postalCode":"28013","country":"%s","isDefault":true}
                """.formatted(DESTINO);
        JsonNode creada = cuerpo(client.post().uri("/api/me/addresses")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated());
        return UUID.fromString(creada.get("id").asText());
    }

    /** Regla de margen del canal escaparate. Sin país = la global, con país = la excepción de ese país. */
    private void sembrarMargen(String pais, String porcentaje) {
        jdbcTemplate.update("""
                INSERT INTO price_rule (id, scope, scope_id, margin_type, margin_value, active, position,
                                        channel, country_code, description, created_at, updated_at)
                VALUES (gen_random_uuid(), 'GLOBAL', NULL, 'PERCENTAGE', CAST(? AS NUMERIC), true, 0,
                        'STOREFRONT', ?, 'Margen de laboratorio', now(), now())
                """, porcentaje, pais);
    }

    /**
     * Tasas de laboratorio, redondas a propósito para que el número esperado se pueda comprobar de
     * cabeza. El yen y el peso chileno se siembran porque NO tienen céntimos: son los que delatan un
     * redondeo a dos decimales colado donde no toca.
     */
    private void sembrarDivisas() {
        insertarDivisa("USD", "US Dollar", "$", "US", "en-US", "1.00000000");
        insertarDivisa("EUR", "Euro", "€", "ES", "es-ES", "0.92000000");
        insertarDivisa("SEK", "Swedish Krona", "kr", "SE", "sv-SE", "10.00000000");
        insertarDivisa("MXN", "Mexican Peso", "$", "MX", "es-MX", "3.00000000");
        insertarDivisa("JPY", "Japanese Yen", "¥", "JP", "ja-JP", "150.00000000");
        insertarDivisa("CLP", "Chilean Peso", "$", "CL", "es-CL", "900.00000000");
        insertarDivisa("CNY", "Chinese Yuan", "¥", "CN", "zh-CN", "8.00000000");

        Map<String, BigDecimal> tasas = new HashMap<>();
        tasas.put("USD", new BigDecimal("1.00000000"));
        tasas.put("EUR", new BigDecimal("0.92000000"));
        tasas.put("SEK", new BigDecimal("10.00000000"));
        tasas.put("MXN", new BigDecimal("3.00000000"));
        tasas.put("JPY", new BigDecimal("150.00000000"));
        tasas.put("CLP", new BigDecimal("900.00000000"));
        tasas.put("CNY", new BigDecimal("8.00000000"));
        currencyRateService.applyBulkSync(tasas);
    }

    private void insertarDivisa(String codigo, String nombre, String simbolo, String pais, String locale,
            String tasa) {
        jdbcTemplate.update("""
                INSERT INTO currency_rate (id, code, name, symbol, country_code, locale, rate_vs_usd, active,
                                           last_synced_at, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, CAST(? AS NUMERIC), true, now(), now(), now())
                """, codigo, nombre, simbolo, pais, locale, tasa);
    }
}
