package com.nexaplatform.dropshipping.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjFulfillmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Comprar con dos transportistas a la vez.
 *
 * <p>Certifica lo que ninguna prueba de una sola pieza puede ver: que la mercancía decide quién puede
 * llevarla, que las dos listas llegan fundidas al cliente, y que <b>lo que elige es lo que se le cobra y
 * por quien se le despacha</b>. Los tres fallos que esto vigila han ocurrido de verdad en este proyecto:
 *
 * <ul>
 *   <li>ofrecer la línea de ropa para algo que no es textil, que acaba en guía rechazada con el pedido
 *       ya cobrado;</li>
 *   <li>cotizar el cobro contra un transportista distinto del que cotizó la pantalla, que hace que el
 *       cliente elija una cosa y pague otra;</li>
 *   <li>perder el rastro de quién lleva el envío, que al despachar obliga a adivinar.</li>
 * </ul>
 *
 * <p>Los dos transportistas van sustituidos: aquí se certifica NUESTRA lógica de reparto y cobro, no la
 * API de nadie. Llamar a CJ de verdad ataría la prueba a la red y a un límite de una petición por
 * segundo.
 */
@TestPropertySource(properties = { "nexadrop.cj.enabled=true", "nexadrop.cj.api-key=CJ0@api@laboratorio" })
@DisplayName("Certificación · comprar con dos transportistas")
class CompraConDosTransportistasIT extends BaseIntegration {

    private static final String DESTINO = "ES";
    private static final int IVA_BPS = 2100;

    /** La línea de ropa de YunExpress: la más barata, y solo admite textil. */
    private static final ShippingOption ROPA = new ShippingOption("FZZXR", "Apparel line", 700, 5, 8);
    /** La generalista de YunExpress: admite de todo, y es la más cara. */
    private static final ShippingOption GENERAL = new ShippingOption("THPHR", "Global line", 950, 6, 10);
    /** La de CJ: en medio de las dos. */
    private static final ShippingOption DE_CJ =
            new ShippingOption("1868922929754472449", "CJPacket Ordinary", 850, 4, 8);

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @MockitoBean
    private FulfillmentProvider yunexpress;
    @MockitoBean
    private CjFulfillmentService cj;

    @Autowired
    private MarginService marginService;

    private UUID clienteId;
    private String tokenCliente;
    private UUID productoTextil;
    private UUID productoNoTextil;
    private UUID direccionId;

    @BeforeEach
    void escenario() {
        vaciarCaches();
        marginService.invalidateCache();
        clienteId = UUID.randomUUID();
        tokenCliente = jwt.userToken(clienteId, "dos-" + clienteId + "@nx036.local", "USER");
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())",
                clienteId, "dos-" + clienteId + "@nx036.local");
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, 5000000, 0, 'USD', 'ACTIVE', now(), now())", clienteId);
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                DESTINO, IVA_BPS);

        // 6109 son camisetas de punto: textil. 6912 es vajilla de cerámica: no lo es.
        productoTextil = insertarProducto("610910");
        productoNoTextil = insertarProducto("691200");
        direccionId = crearDireccion();

        when(yunexpress.nombre()).thenReturn("YUNEXPRESS");
        when(cj.nombre()).thenReturn(CjFulfillmentService.NOMBRE);
        when(yunexpress.isSupported(anyString())).thenReturn(true);
        when(cj.isSupported(anyString())).thenReturn(true);
        when(yunexpress.quote(anyString(), any())).thenReturn(new ShippingQuote(true, DESTINO,
                ROPA.amountUsdCents(), "Standard Shipping", "Standard Shipping", 5, 8, "EU",
                List.of(ROPA, GENERAL)));
        when(cj.quote(anyString(), any())).thenReturn(new ShippingQuote(true, DESTINO,
                DE_CJ.amountUsdCents(), "Standard Shipping", "Standard Shipping", 4, 8, "EU",
                List.of(DE_CJ)));
    }

    /* ================================================================================== */

    @Test
    @DisplayName("un pedido de ropa ve las opciones de los DOS, mezcladas y de más barata a más cara")
    void elPedidoDeRopaVeLosDos() {
        JsonNode previa = cotizar(productoTextil);

        assertThat(codigosDe(previa)).containsExactly(ROPA.code(), DE_CJ.code(), GENERAL.code());
        assertThat(previa.get("options").get(0).get("amountUsdCents").asInt()).isEqualTo(700);
    }

    @Test
    @DisplayName("un pedido que NO es ropa pierde la línea de ropa, y sigue teniendo con quién enviarse")
    void elPedidoQueNoEsRopaPierdeEsaLinea() {
        JsonNode previa = cotizar(productoNoTextil);

        assertThat(codigosDe(previa))
                .as("ofrecer FZZXR para una vajilla acaba en guía rechazada con el pedido ya cobrado")
                .doesNotContain(ROPA.code())
                .containsExactly(DE_CJ.code(), GENERAL.code());
    }

    @Test
    @DisplayName("elegir la opción de CJ la cobra a ella y deja el pedido marcado como de CJ")
    void elegirCjSeCobraYSeAnotaCj() {
        JsonNode pedido = comprar(productoTextil, DE_CJ.code());

        assertThat(transportistaDe(pedido)).isEqualTo(CjFulfillmentService.NOMBRE);
        assertThat(canalDe(pedido)).isEqualTo(DE_CJ.code());
        assertThat(nombreDeLaLineaDe(pedido))
                .as("CJ exige el NOMBRE para emitir la guía: sin guardarlo hay que volver a cotizar")
                .isEqualTo(DE_CJ.name());
    }

    @Test
    @DisplayName("elegir la de YunExpress la cobra a ella: el cobro no se cae al otro transportista")
    void elegirYunexpressSeCobraYunexpress() {
        JsonNode pedido = comprar(productoTextil, GENERAL.code());

        assertThat(transportistaDe(pedido)).isEqualTo("YUNEXPRESS");
        assertThat(canalDe(pedido))
                .as("si el cobro cotizara contra uno solo, la opción del otro se daría por inválida")
                .isEqualTo(GENERAL.code());
    }

    @Test
    @DisplayName("lo que se enseña al elegir es lo que se cobra, al céntimo")
    void loEnseniadoEsLoCobrado() {
        JsonNode previa = cotizar(productoTextil);
        String totalEnseñado = previa.get("totalFormatted").asText();

        JsonNode pedido = comprar(productoTextil, ROPA.code());

        assertThat(pedido.get("totalFormatted").asText())
                .as("la previa cotiza la más barata y sin elegir se cobra esa misma")
                .isEqualTo(totalEnseñado);
    }

    @Test
    @DisplayName("si un transportista se cae, se sigue vendiendo con el otro")
    void unTransportistaCaidoNoSeLlevaLaVenta() {
        when(cj.quote(anyString(), any())).thenThrow(new IllegalStateException("CJ no responde"));

        JsonNode previa = cotizar(productoTextil);

        assertThat(codigosDe(previa))
                .as("perder la venta porque un proveedor está caído, teniendo otro, es regalarla")
                .containsExactly(ROPA.code(), GENERAL.code());
    }

    /* ==================================================================================
     *  Peticiones y utilidades
     * ================================================================================== */

    private JsonNode cotizar(UUID productoId) {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":null,"shippingOptionCode":null,
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(DESTINO, productoId);
        return cuerpo(client.post().uri("/api/shipping/quote")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isOk());
    }

    private JsonNode comprar(UUID productoId, String opcion) {
        String cuerpo = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","shippingOptionCode":"%s",
                 "items":[{"productId":"%s","quantity":1}]}
                """.formatted(direccionId, opcion, productoId);
        return cuerpo(client.post().uri("/api/me/orders/checkout")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated());
    }

    private static List<String> codigosDe(JsonNode previa) {
        return previa.findValuesAsText("code");
    }

    private String transportistaDe(JsonNode pedido) {
        return jdbcTemplate.queryForObject("SELECT shipping_carrier FROM customer_order WHERE id = ?",
                String.class, UUID.fromString(pedido.get("id").asText()));
    }

    private String canalDe(JsonNode pedido) {
        return jdbcTemplate.queryForObject("SELECT shipping_channel_code FROM customer_order WHERE id = ?",
                String.class, UUID.fromString(pedido.get("id").asText()));
    }

    private String nombreDeLaLineaDe(JsonNode pedido) {
        return jdbcTemplate.queryForObject("SELECT shipping_channel_name FROM customer_order WHERE id = ?",
                String.class, UUID.fromString(pedido.get("id").asText()));
    }

    private static JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        try {
            return JSON.readTree(new String(respuesta.expectBody().returnResult().getResponseBody()));
        } catch (Exception e) {
            throw new IllegalStateException("respuesta ilegible", e);
        }
    }

    private UUID insertarProducto(String hsCode) {
        UUID proveedorId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, created_at, updated_at)"
                + " VALUES (?, ?, '1688', 'Proveedor de prueba', now(), now())",
                proveedorId, "sup-" + proveedorId);
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, title_zh,"
                + " status, moq, base_price, currency, hs_code, weight_grams, created_at, updated_at)"
                + " VALUES (?, ?, ?, '1688', ?, '棉质T恤', 'ACTIVE', 1, 20.0000, 'USD', ?, 500,"
                + " now(), now())",
                id, "producto-" + sufijo, "ext-" + sufijo, proveedorId, hsCode);
        for (String[] t : new String[][] { { "es", "Artículo de prueba" }, { "en", "Test item" },
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
        return UUID.fromString(cuerpo(client.post().uri("/api/me/addresses")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated()).get("id").asText());
    }
}
