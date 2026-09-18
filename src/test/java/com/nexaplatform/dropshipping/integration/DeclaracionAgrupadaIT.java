package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.ParcelDeclaration;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Lo cotizado, lo cobrado y lo declarado cuentan LO MISMO.
 *
 * <p>El derecho temporal de la Unión son 3 EUR por <b>línea de declaración</b>, y lo que separa una línea
 * de otra es la descripción con la que viaja cada mercancía. Cuando dos productos comparten un grupo
 * aprobado viajan con la misma descripción y son UNA línea: 3 EUR, no 6.
 *
 * <p><b>Por qué esta clase y no las unitarias.</b> Entre la vista previa y la guía hay tres resoluciones
 * distintas de la misma descripción —el checkout la calcula, el pedido la congela y el despacho la
 * transmite— y basta con que UNA deje de mirar el grupo para que el cliente vea un importe y se le cobre
 * otro. Ese descuadre no lo detecta ninguna prueba de un solo tramo: cada una seguiría en verde midiendo
 * su mitad. Aquí se compra de verdad por HTTP y se comprueba que los tres números coinciden.
 *
 * <p><b>El caso de control es parte de la prueba.</b> Sin aprobar el grupo, el mismo carrito paga el doble
 * y la guía emite dos líneas. Sin él, esta clase pasaría igual el día que la agrupación dejara de
 * aplicarse: estaría midiendo que el cálculo devuelve un número, no que devuelve ESTE.
 */
@TestPropertySource(properties = {
        "nexadrop.fulfillment.sync-enabled=false",
        // Sin límite de bulto: los dos artículos viajan juntos. El reparto en bultos —que también decide
        // cuántas veces se cobra el derecho— ya lo certifica CustomsDutyIT; aquí se mide la AGRUPACIÓN.
        "nexadrop.yunexpress.max-parcel-weight-grams=0",
        "nexadrop.yunexpress.max-parcel-value-cents=0",
        "nexadrop.yunexpress.max-parcel-units=0"
})
class DeclaracionAgrupadaIT extends BaseIntegration {

    private static final String COTIZAR = "/api/shipping/quote";
    private static final String CHECKOUT = "/api/me/orders/checkout";
    private static final String DIRECCIONES = "/api/me/addresses";

    private static final String PAIS = "ES";

    /** La terna que comparten los dos productos, tal y como se teclea en el catálogo. */
    private static final String HS6 = "620443";
    private static final String MATERIAL = "Polyester woven fabric";
    private static final String USO = "Womens dress, daily wear";

    /** La descripción genérica aprobada: la que hace que la aduana cuente UNA línea en vez de dos. */
    private static final String DESCRIPCION_DEL_GRUPO = "Women's or girls' dresses, of synthetic fibres";
    private static final String DESCRIPCION_ZH_DEL_GRUPO = "女式合成纤维制连衣裙";

    /** Derecho por línea de declaración, sembrado en dólares para que no dependa de la tasa del día. */
    private static final int ARANCEL_POR_LINEA_CENTS = 300;
    private static final int PORTE_CENTS = 900;

    private static final String CANAL = "FZZXR";
    private static final String TRANSPORTISTA = "YUNEXPRESS";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @MockitoBean
    private FulfillmentProvider fulfillment;

    /**
     * El repositorio real, para armar el servicio de despacho a mano.
     *
     * <p>No se puede inyectar el bean: {@code YunExpressFulfillmentService} <b>es</b> el
     * {@link FulfillmentProvider} de la aplicación, así que el doble de arriba ya ocupa su sitio.
     * {@code declaredParcels} no necesita ninguna de las otras colaboraciones —solo el pedido y los
     * productos—, que es justo por lo que se puede construir así sin falsear nada de lo que se mide.
     */
    @Autowired
    private ProductRepository productos;

    @Autowired
    private OrderRepository pedidos;

    @Autowired
    private MarginService marginService;

    @Autowired
    private PlatformTransactionManager transacciones;

    private String tokenCliente;
    private UUID vestidoAzul;
    private UUID vestidoRojo;
    private UUID direccionId;

    @BeforeEach
    void prepararEscenario() {
        // Las reglas de margen se cachean al arrancar el contexto, antes del TRUNCATE: sin invalidar, una
        // regla fantasma marcaría precios que no salen de este escenario.
        marginService.invalidateCache();
        UUID clienteId = UUID.randomUUID();
        tokenCliente = jwt.userToken(clienteId, "compradora@nx036.local", "USER");
        insertarUsuario(clienteId, "compradora@nx036.local");
        acreditarMonedero(clienteId, 100_000L);

        // Dos productos de la MISMA terna con títulos distintos: sin grupo aprobado son dos líneas.
        vestidoAzul = insertarVestido("Blue midi dress", "蓝色连衣裙");
        vestidoRojo = insertarVestido("Red floral dress", "红色碎花连衣裙");

        insertarIva(PAIS, 2100);
        insertarReglaDeAduana(PAIS, ARANCEL_POR_LINEA_CENTS);

        when(fulfillment.isSupported(anyString())).thenReturn(true);
        when(fulfillment.nombre()).thenReturn(TRANSPORTISTA);
        when(fulfillment.quote(anyString(), any())).thenReturn(new ShippingQuote(true, PAIS, PORTE_CENTS,
                "Standard Shipping", "Standard Shipping", 5, 8, "EU",
                List.of(new ShippingOption(CANAL, "Apparel line", PORTE_CENTS, 5, 8))));

        direccionId = crearDireccion();
    }

    @Test
    @DisplayName("con el grupo aprobado, la vista previa, el pedido y la guía cuentan UNA línea de 3 EUR")
    void loCotizadoLoCobradoYLoDeclaradoCuentanLoMismo() {
        aprobarElGrupo();

        // 1 · La vista previa: el arancel del carrito es UN derecho, no dos.
        JsonNode previa = cotizar();
        assertThat(previa.get("customsHandlingUsdCents").asInt())
                .as("dos vestidos de la misma terna son UNA línea de declaración")
                .isEqualTo(ARANCEL_POR_LINEA_CENTS);

        // 2 · El pedido cobra ESO y congela con qué descripción se declaró. Sin el snapshot, aprobar o
        // retirar el grupo después de cobrar cambiaría lo que se declara y el pedido dejaría de cuadrar.
        UUID pedidoId = pagarConMonedero();
        assertThat(enteroDe("SELECT customs_duty_cents FROM customer_order WHERE id = ?", pedidoId))
                .isEqualTo(ARANCEL_POR_LINEA_CENTS);
        assertThat(descripcionesCongeladas(pedidoId))
                .as("las dos líneas se congelan con la descripción del grupo")
                .containsExactly(DESCRIPCION_DEL_GRUPO, DESCRIPCION_DEL_GRUPO);
        assertThat(descripcionesCongeladasZh(pedidoId))
                .as("y con su chino: una línea de la declaración lleva un solo EName y un solo CName")
                .containsExactly(DESCRIPCION_ZH_DEL_GRUPO, DESCRIPCION_ZH_DEL_GRUPO);

        // 3 · La guía transmite UNA sola línea, con los dos artículos dentro.
        List<ParcelDeclaration> declarado = declaracionDe(pedidoId);
        assertThat(declarado).hasSize(1);
        assertThat(declarado.get(0).eName()).isEqualTo(DESCRIPCION_DEL_GRUPO);
        assertThat(declarado.get(0).cName()).isEqualTo(DESCRIPCION_ZH_DEL_GRUPO);
        assertThat(declarado.get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("sin aprobar el grupo, el mismo carrito paga dos derechos y la guía emite dos líneas")
    void sinAprobarNadaAgrupaYSeCobraDeMas() {
        // El control de la prueba anterior. Un grupo sin firmar NO agrupa: cada producto sigue siendo su
        // propia línea. Se cobra de más en el peor caso, nunca de menos, que es la salvaguarda que impide
        // que cargar catálogo abarate el arancel por accidente.
        sembrarGrupo(null);

        assertThat(cotizar().get("customsHandlingUsdCents").asInt())
                .isEqualTo(2 * ARANCEL_POR_LINEA_CENTS);

        UUID pedidoId = pagarConMonedero();
        assertThat(declaracionDe(pedidoId)).hasSize(2);
    }

    @Test
    @DisplayName("con la agrupación apagada en el país, ese destino vuelve a contar una línea por producto")
    void elInterruptorDelPaisDevuelveElDestinoAlComportamientoDeSiempre() {
        // El freno de mano por destino: si una aduana empezara a contar distinto, se apaga ESE país sin
        // desplegar y sin tocar los grupos aprobados, que siguen valiendo para los otros 26.
        aprobarElGrupo();
        jdbcTemplate.update("UPDATE country_customs_rule SET group_declaration_lines = false"
                + " WHERE country_code = ?", PAIS);

        assertThat(cotizar().get("customsHandlingUsdCents").asInt())
                .isEqualTo(2 * ARANCEL_POR_LINEA_CENTS);
        assertThat(declaracionDe(pagarConMonedero())).hasSize(2);
    }

    /* ==================================================================================
     *  El recorrido del cliente
     * ================================================================================== */

    private JsonNode cotizar() {
        String cuerpo = """
                {"country":"%s","region":null,"couponCode":null,"shippingOptionCode":"%s",
                 "items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":1}]}
                """.formatted(PAIS, CANAL, vestidoAzul, vestidoRojo);
        return cuerpo(client.post().uri(COTIZAR).header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isOk());
    }

    private UUID pagarConMonedero() {
        String cuerpo = """
                {"shippingAddressId":"%s","paymentMethod":"WALLET","shippingOptionCode":"%s",
                 "items":[{"productId":"%s","quantity":1},{"productId":"%s","quantity":1}]}
                """.formatted(direccionId, CANAL, vestidoAzul, vestidoRojo);
        JsonNode pedido = cuerpo(client.post().uri(CHECKOUT)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                // La clave de idempotencia es OBLIGATORIA en todo lo que mueve dinero: sin ella el
                // servidor responde 400. Un arnés de prueba es un cliente más y tiene que mandarla.
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated());
        return UUID.fromString(pedido.get("id").asText());
    }

    private UUID crearDireccion() {
        String cuerpo = """
                {"fullName":"Compradora de prueba","phone":"+34600000000","line1":"Gran Via 1",
                 "city":"Madrid","state":"M","postalCode":"28013","country":"%s","isDefault":true}
                """.formatted(PAIS);
        JsonNode creada = cuerpo(client.post().uri(DIRECCIONES)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated());
        return UUID.fromString(creada.get("id").asText());
    }

    /**
     * Lo que se le transmitiría al transportista para ese pedido.
     *
     * <p>Va dentro de una transacción porque las líneas del pedido se cargan en diferido: leerlas fuera
     * de sesión revienta con {@code LazyInitializationException}. En producción esto ocurre dentro del
     * despacho, que sí es transaccional.
     */
    private List<ParcelDeclaration> declaracionDe(UUID pedidoId) {
        YunExpressFulfillmentService despacho =
                new YunExpressFulfillmentService(null, null, null, null, productos, null, null, null);
        return new TransactionTemplate(transacciones).execute(estado ->
                despacho.declaredParcels(pedidos.findById(pedidoId).orElseThrow()));
    }

    /* ==================================================================================
     *  Semillas
     * ================================================================================== */

    /** El grupo de la terna con su firma puesta: a partir de aquí los dos vestidos son una sola línea. */
    private void aprobarElGrupo() {
        sembrarGrupo(Instant.now());
    }

    /**
     * @param aprobadoEn cuándo se firmó, o {@code null} para dejarlo sin aprobar — que es lo que hace que
     *                   NO agrupe
     */
    private void sembrarGrupo(Instant aprobadoEn) {
        jdbcTemplate.update("INSERT INTO customs_declaration_group (id, hs6, material, usage_code, ename,"
                + " cname, product_count, approved_at, approved_by, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 2, ?, ?, now(), now())",
                HS6, MATERIAL.toUpperCase(), USO.toUpperCase(), DESCRIPCION_DEL_GRUPO,
                DESCRIPCION_ZH_DEL_GRUPO, aprobadoEn == null ? null : java.sql.Timestamp.from(aprobadoEn),
                aprobadoEn == null ? null : "admin@nx036.local");
    }

    /**
     * Un vestido con todo lo que la aduana exige: título en inglés (el EName de siempre), título en chino
     * con ideogramas de verdad, partida, material, uso, peso y precio.
     */
    private UUID insertarVestido(String tituloEn, String tituloZh) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                + " base_price, currency, shipping_cny, iva_cny, weight_grams, hs_code, customs_material,"
                + " customs_usage, country_of_origin, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'TEST', ?, 'ACTIVE', 1, 20.0000, 'USD', 0, 0, 300, ?, ?, ?, 'CN',"
                + " now(), now())",
                id, "vestido-" + sufijo, "ext-" + sufijo, tituloZh, HS6, MATERIAL, USO);
        insertarTraduccion(id, "en", tituloEn);
        insertarTraduccion(id, "zh", tituloZh);
        insertarTraduccion(id, "es", tituloEn);
        return id;
    }

    private void insertarTraduccion(UUID productoId, String idioma, String titulo) {
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, created_at,"
                + " updated_at) VALUES (gen_random_uuid(), ?, ?, ?, now(), now())",
                productoId, idioma, titulo);
    }

    private void insertarUsuario(UUID id, String email) {
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, language, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, 'es', now(), now())", id, email);
    }

    private void acreditarMonedero(UUID id, long centimos) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents,"
                + " currency_default, status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", id, centimos);
    }

    private void insertarIva(String pais, int rateBps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, 'IVA', ?, true, now(), now())",
                pais, rateBps);
    }

    /** Regla de aduana del destino con el derecho por línea en USD y sin franquicia configurada. */
    private void insertarReglaDeAduana(String pais, int derechoUsdCents) {
        jdbcTemplate.update("INSERT INTO country_customs_rule (id, country_code, tax_mode,"
                + " de_minimis_amount, de_minimis_currency, over_threshold_policy, per_article_fee_amount,"
                + " per_article_fee_currency, group_declaration_lines, active, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, 'DDP', 0, 'USD', 'SURCHARGE', ?::numeric, 'USD', true,"
                + " true, now(), now())",
                pais, BigDecimal.valueOf(derechoUsdCents, 2).toPlainString());
    }

    /* ==================================================================================
     *  Utilidades
     * ================================================================================== */

    private List<String> descripcionesCongeladas(UUID pedidoId) {
        return jdbcTemplate.queryForList("SELECT declared_description FROM order_item WHERE order_id = ?"
                + " ORDER BY declared_description", String.class, pedidoId);
    }

    private List<String> descripcionesCongeladasZh(UUID pedidoId) {
        return jdbcTemplate.queryForList("SELECT declared_description_zh FROM order_item WHERE order_id = ?"
                + " ORDER BY declared_description_zh", String.class, pedidoId);
    }

    private Integer enteroDe(String sql, UUID id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private JsonNode cuerpo(WebTestClient.ResponseSpec respuesta) {
        String texto = respuesta.expectBody(String.class).returnResult().getResponseBody();
        try {
            return JSON.readTree(texto == null ? "{}" : texto);
        } catch (JacksonException e) {
            throw new IllegalStateException("Respuesta no es JSON: " + texto, e);
        }
    }
}
