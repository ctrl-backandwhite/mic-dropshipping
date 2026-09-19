package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.security.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partner API de punta a punta: la credencial se crea de verdad, se canjea de verdad por un JWT en
 * {@code /oauth2/token} y con ese token se golpea cada familia de endpoints — incluidos los cruces que TIENEN
 * que fallar. Cubre además los webhooks: el entrante, con su matriz completa de firmas, y el saliente.
 *
 * <p><b>Por qué el token se pide al servidor y no se fabrica.</b> Un token firmado a mano en el test
 * comprueba la cadena de autorización, pero no que el flujo {@code client_credentials} conceda REALMENTE los
 * ámbitos pedidos. Aquí la clave se crea por {@code /api/me/api-keys}, se canjea por Basic auth y el JWT
 * resultante lleva los scopes que concedió el servidor de autorización: si un día se concediera de más, este
 * test lo ve.
 *
 * <p><b>Semántica de las aserciones de autorización.</b> «Permitido» = el estado NO es 401 ni 403 (puede ser
 * 200 o un 4xx de negocio); lo que se afirma es que la autorización no bloqueó. «Prohibido» = 401 exacto
 * cuando falta la credencial y 403 exacto cuando el ámbito no alcanza.
 *
 * <p><b>Webhook entrante.</b> Con firma válida el cuerpo llega al negocio y, al referenciar un SKU que no
 * existe, responde 422. Ese 422 es la prueba de que la firma se aceptó: sin ella la petición muere antes, en
 * el 401. Es el discriminante que se usa en toda la matriz.
 */
class PartnerApiIT extends BaseIntegration {

    private static final String API_KEYS = "/api/me/api-keys";
    private static final String TOKEN = "/oauth2/token";
    private static final String PARTNER_CATALOG = "/api/v1/partner/catalog/products";
    private static final String PARTNER_ORDERS = "/api/v1/partner/orders";
    private static final String ADMIN_SUBS = "/api/admin/webhooks/subscriptions";
    private static final String ADMIN_DASHBOARD = "/api/admin/dashboard/metrics";
    private static final String AUTH = "Authorization";
    private static final String SCOPE_CATALOG = "catalog.read";
    private static final String SCOPE_ORDERS = "orders.write";
    private static final String SCOPE_SHOP = "shop.sync";
    private static final String SLUG = "abrigo-de-invierno";
    private static final String INBOUND_SECRET = "secreto-compartido-de-la-tienda";
    private static final String FIRMA = "X-NX-Signature";

    /** Cuerpo canónico de un pedido entrante: válido de forma, con un SKU que el catálogo no conoce. */
    private static final String CUERPO_PEDIDO = """
            {"line_items":[{"sku":"SKU-QUE-NO-EXISTE","quantity":1}],
             "shipping_address":{"name":"Ada Lovelace","address1":"1 Babbage Way","city":"London",
             "zip":"EC1A1","country_code":"GB"}}""";

    @Autowired
    private CacheManager cacheManager;

    /**
     * La Partner API está limitada a 1 petición por minuto y credencial en el plan sandbox
     * ({@code RateLimitFilter.partnerRule}), y los cubos viven en memoria del proceso: no los toca el
     * TRUNCATE de la clase base, así que sin vaciarlos la cuota gastada por un caso llega al siguiente y
     * el 401/403 que se mide sale convertido en 429. La cuota tiene sus propios casos; aquí estorba.
     */
    @Autowired
    private RateLimitFilter rateLimitFilter;

    private UUID userId;
    private String userToken;
    private String adminToken;

    @BeforeEach
    void seedPartner() {
        limpiarCuotaDePeticiones();
        clearAllCaches();
        seedCurrencies();
        userId = insertUser("partner@nx036.test");
        userToken = jwt.userToken(userId, "partner@nx036.test", "USER");
        adminToken = jwt.userToken("ADMIN");
        seedProductoVisible();
    }

    /* ============================================================================================
     * CLAVES DE API (autoservicio)
     * ========================================================================================== */

    @Test
    @DisplayName("Crear una clave devuelve el secreto UNA vez, y el listado ya no lo enseña")
    void crearYListarClaves() {
        JsonNode creada = crearClave("Mi integración", List.of(SCOPE_CATALOG));

        assertThat(creada.get("clientId").asText()).startsWith("pk_");
        assertThat(creada.get("clientSecret").asText()).startsWith("sk_");

        JsonNode listado = client.get().uri(API_KEYS).header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(listado).isNotNull();
        assertThat(listado.size()).isEqualTo(1);
        assertThat(listado.get(0).get("clientId").asText()).isEqualTo(creada.get("clientId").asText());
        assertThat(listado.get(0).has("clientSecret")).as("el secreto no se vuelve a publicar").isFalse();
    }

    @Test
    @DisplayName("Revocar una clave la borra del listado")
    void revocarClave() {
        String clientId = crearClave("Temporal", List.of(SCOPE_CATALOG)).get("clientId").asText();

        client.delete().uri(API_KEYS + "/" + clientId).header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isNoContent();

        JsonNode listado = client.get().uri(API_KEYS).header(AUTH, bearer(userToken)).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(listado).isNotNull();
        assertThat(listado.size()).isZero();
    }

    @Test
    @DisplayName("Caso borde: un ámbito inventado se rechaza (422) y no crea la clave")
    void ambitoInventado() {
        client.post().uri(API_KEYS).header(AUTH, bearer(userToken)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Mala\",\"scopes\":[\"catalog.write\"]}").exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("Caso borde: sin nombre la clave no se crea (400 de validación)")
    void claveSinNombre() {
        client.post().uri(API_KEYS).header(AUTH, bearer(userToken)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"scopes\":[\"catalog.read\"]}").exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("Caso borde: la sexta clave se rechaza por cuota (máximo 5 por usuario)")
    void cuotaDeClaves() {
        for (int i = 0; i < 5; i++) {
            crearClave("Clave " + i, List.of(SCOPE_CATALOG));
        }

        client.post().uri(API_KEYS).header(AUTH, bearer(userToken)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"La sexta\",\"scopes\":[\"catalog.read\"]}").exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("Caso borde: gestionar claves sin credencial es 401")
    void clavesSinCredencial() {
        client.get().uri(API_KEYS).exchange().expectStatus().isUnauthorized();
        client.post().uri(API_KEYS).contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"X\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    /* ============================================================================================
     * ÁMBITOS: cada scope contra cada familia de endpoints
     * ========================================================================================== */

    @Test
    @DisplayName("Una clave con catalog.read lee el catálogo del partner")
    void scopeCatalogLeeCatalogo() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);

        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(token)).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Una clave con catalog.read NO puede tocar pedidos (403)")
    void scopeCatalogNoTocaPedidos() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);

        // Las tres entradas de /orders comparten cubo de cuota (misma regla, misma credencial) y en
        // sandbox solo cabe UNA por minuto. Se vacía entre llamadas para que lo que responda cada una
        // sea la decisión de AUTORIZACIÓN y no el freno de caudal, que es otro asunto.
        client.get().uri(PARTNER_ORDERS).header(AUTH, bearer(token)).exchange().expectStatus().isForbidden();
        limpiarCuotaDePeticiones();
        client.post().uri(PARTNER_ORDERS).header(AUTH, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isForbidden();
        limpiarCuotaDePeticiones();
        client.get().uri(PARTNER_ORDERS + "/" + UUID.randomUUID()).header(AUTH, bearer(token)).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Una clave con orders.write entra en pedidos pero NO en el catálogo (403)")
    void scopeOrdersNoLeeCatalogo() {
        String token = tokenDeClave(List.of(SCOPE_ORDERS), SCOPE_ORDERS);

        int estado = client.post().uri(PARTNER_ORDERS).header(AUTH, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().returnResult(Void.class)
                .getStatus().value();
        assertThat(estado).as("orders.write no debe bloquearse en /orders").isNotIn(401, 403);

        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(token)).exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Una clave con shop.sync no abre ni catálogo ni pedidos (403 en ambos)")
    void scopeShopNoAbreNada() {
        String token = tokenDeClave(List.of(SCOPE_SHOP), SCOPE_SHOP);

        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(token)).exchange().expectStatus().isForbidden();
        client.post().uri(PARTNER_ORDERS).header(AUTH, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Una clave con los tres ámbitos entra en catálogo y en pedidos")
    void claveConTodosLosAmbitos() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG, SCOPE_ORDERS, SCOPE_SHOP),
                SCOPE_CATALOG + " " + SCOPE_ORDERS);

        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(token)).exchange().expectStatus().isOk();
        int estado = client.post().uri(PARTNER_ORDERS).header(AUTH, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().returnResult(Void.class)
                .getStatus().value();
        assertThat(estado).isNotIn(401, 403);
    }

    @Test
    @DisplayName("Caso borde: pedir en el canje un ámbito que la clave no tiene se rechaza")
    void canjeDeAmbitoNoConcedido() {
        JsonNode clave = crearClave("Solo catálogo", List.of(SCOPE_CATALOG));

        client.post().uri(TOKEN).header(HttpHeaders.AUTHORIZATION, basic(clave))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("grant_type=client_credentials&scope=" + SCOPE_ORDERS).exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    @DisplayName("Caso borde: un secreto equivocado no obtiene token")
    void secretoEquivocado() {
        JsonNode clave = crearClave("Con secreto", List.of(SCOPE_CATALOG));
        String basicMalo = "Basic " + Base64.getEncoder().encodeToString(
                (clave.get("clientId").asText() + ":sk_lo-que-sea").getBytes(StandardCharsets.UTF_8));

        client.post().uri(TOKEN).header(HttpHeaders.AUTHORIZATION, basicMalo)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("grant_type=client_credentials&scope=" + SCOPE_CATALOG).exchange()
                .expectStatus().is4xxClientError();
    }

    /* ============================================================================================
     * CRUCES Y AUSENCIA DE CREDENCIAL
     * ========================================================================================== */

    @Test
    @DisplayName("Un token de usuario normal NO vale en la Partner API (403: no lleva ámbitos)")
    void tokenDeUsuarioNoValeEnPartner() {
        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(userToken)).exchange().expectStatus().isForbidden();
        client.get().uri(PARTNER_ORDERS).header(AUTH, bearer(userToken)).exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Ni siquiera un token de ADMIN abre la Partner API: es otra cadena de autorización")
    void tokenDeAdminNoValeEnPartner() {
        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(adminToken)).exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Y al revés: un token de partner no entra en la cadena de admin")
    void tokenDePartnerNoValeEnAdmin() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);

        int estado = client.get().uri(ADMIN_DASHBOARD).header(AUTH, bearer(token)).exchange()
                .returnResult(Void.class).getStatus().value();

        assertThat(estado).as("un token de partner no puede administrar la plataforma").isIn(401, 403);
    }

    @Test
    @DisplayName("Sin credencial la Partner API responde 401 en todas sus familias")
    void sinCredencialEs401() {
        // Sin credencial el cubo de cuota se lleva por IP, así que las cuatro peticiones caerían en dos
        // cubos de una sola ficha. Se vacía entre llamadas: lo que se mide es el 401, no el caudal.
        client.get().uri(PARTNER_CATALOG).exchange().expectStatus().isUnauthorized();
        limpiarCuotaDePeticiones();
        client.get().uri(PARTNER_ORDERS).exchange().expectStatus().isUnauthorized();
        limpiarCuotaDePeticiones();
        client.post().uri(PARTNER_ORDERS).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isUnauthorized();
        limpiarCuotaDePeticiones();
        client.get().uri("/api/v1/partner/catalog/categories").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Caso borde: un token con la firma manipulada es 401, no 403")
    void tokenConFirmaManipulada() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);
        String manipulado = token.substring(0, token.length() - 4) + "AAAA";

        client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(manipulado)).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Caso borde: una cabecera Authorization sin sentido es 401")
    void cabeceraSinSentido() {
        // Ninguna de las dos cabeceras deja un JWT legible, así que las dos caen en el MISMO cubo por IP
        // (una ficha por minuto). Se vacía entre ellas para que la segunda mida el 401 y no el 429.
        client.get().uri(PARTNER_CATALOG).header(AUTH, "Bearer ").exchange().expectStatus().isUnauthorized();
        limpiarCuotaDePeticiones();
        client.get().uri(PARTNER_CATALOG).header(AUTH, "Basic dXNlcjpwYXNz").exchange()
                .expectStatus().isUnauthorized();
    }

    /* ============================================================================================
     * COSTE EN CNY Y MARGEN — tampoco para el partner
     * ========================================================================================== */

    @Test
    @DisplayName("La ficha del partner NO expone el coste ni el margen")
    void partnerNoVeCosteNiMargen() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);

        JsonNode ficha = client.get().uri("/api/v1/partner/catalog/products/" + SLUG)
                .header(AUTH, bearer(token)).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(ficha).isNotNull();
        assertThat(ficha.get("costUsd").isNull()).as("el coste es información interna").isTrue();
        assertThat(ficha.get("retailUsd").isNull()).isTrue();
        assertThat(ficha.get("appliedMarginPercent").isNull()).as("el margen es información interna").isTrue();
        assertThat(ficha.get("baseFormatted").isNull()).isTrue();
        assertThat(ficha.get("ivaFormatted").isNull()).isTrue();
        assertThat(ficha.get("shippingFormatted").isNull()).isTrue();
        // `basePrice` es el importe que se paga al proveedor en CNY y `currency` la etiqueta que lo delata:
        // el integrador tarifica con `displayFormatted`, no con nuestro coste.
        assertThat(ficha.get("basePrice") == null || ficha.get("basePrice").isNull()).isTrue();
        assertThat(ficha.get("currency") == null || ficha.get("currency").isNull()).isTrue();
        assertThat(ficha.get("displayFormatted").asText()).isNotBlank();
    }

    @Test
    @DisplayName("Las variantes del partner llevan precio de venta, no el coste del proveedor")
    void partnerVeSoloPrecioDeVenta() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);
        UUID productId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE slug = ?", UUID.class, SLUG);
        jdbcTemplate.update("INSERT INTO product_variant (id, product_id, external_id, sku, title, price, stock, "
                + "options_json, active) VALUES (gen_random_uuid(), ?, 'EXT-V1', 'SKU-V1', 'Talla M', ?, 4, "
                + "CAST('{\"Talla\":\"M\"}' AS jsonb), true)", productId, new BigDecimal("150.0000"));

        JsonNode variantes = client.get().uri("/api/v1/partner/catalog/products/" + productId + "/variants")
                .header(AUTH, bearer(token)).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(variantes).isNotNull();
        assertThat(variantes.size()).isEqualTo(1);
        JsonNode v = variantes.get(0);
        // 150 es el coste en yuanes del proveedor; lo publicado es el precio de venta convertido.
        assertThat(new BigDecimal(v.get("price").asText())).isNotEqualByComparingTo(new BigDecimal("150"));
        assertThat(campos(v)).doesNotContain("cost", "costUsd", "basePrice", "priceCny", "appliedMarginPercent");
    }

    /**
     * Cierre del defecto que este caso dejó documentado: el listado ({@code ProductSummaryView}) publicaba
     * {@code basePrice} con el coste del proveedor en CNY y {@code currency}='CNY' a cualquiera, incluida
     * la API de partners. Junto al precio de venta que viaja al lado, el margen de la plataforma se
     * despejaba con una división. Hoy {@code ProductMapper.toSummary} solo se los da al ADMIN, así que el
     * caso ya se puede exigir.
     */
    @Test
    @DisplayName("El listado del partner NO publica el coste en CNY")
    void partnerNoDebeVerCosteEnElListado() {
        String token = tokenDeClave(List.of(SCOPE_CATALOG), SCOPE_CATALOG);

        JsonNode page = client.get().uri(PARTNER_CATALOG).header(AUTH, bearer(token)).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(page).isNotNull();
        assertThat(page.get("items").size()).as("el producto sembrado tiene que salir en el listado").isPositive();
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("basePrice") == null || item.get("basePrice").isNull()).isTrue();
        assertThat(item.get("currency") == null || item.get("currency").isNull()).isTrue();
        // `priceUsd` es el retail canónico en dólares: al integrador le corresponde `displayFormatted`.
        assertThat(item.get("priceUsd") == null || item.get("priceUsd").isNull()).isTrue();
        assertThat(item.get("displayFormatted").asText()).isNotBlank();
    }

    /* ============================================================================================
     * WEBHOOK ENTRANTE — matriz de firmas
     * ========================================================================================== */

    @Test
    @DisplayName("Webhook entrante: con la firma VÁLIDA el cuerpo llega al negocio (422 por SKU desconocido)")
    void webhookFirmaValida() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, cuerpo, hmacHex(INBOUND_SECRET, cuerpo)).expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("Webhook entrante: se acepta también la firma con el prefijo 'sha256=' (compatibilidad)")
    void webhookFirmaConPrefijo() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, cuerpo, "sha256=" + hmacHex(INBOUND_SECRET, cuerpo)).expectStatus().isEqualTo(422);
    }

    @Test
    @DisplayName("Webhook entrante: firma MANIPULADA → 401")
    void webhookFirmaManipulada() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);
        String firma = hmacHex(INBOUND_SECRET, cuerpo);
        String manipulada = firma.substring(0, firma.length() - 1) + (firma.endsWith("a") ? "b" : "a");

        postWebhook(shopId, cuerpo, manipulada).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Webhook entrante: cuerpo ALTERADO tras firmar → 401")
    void webhookCuerpoAlterado() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] original = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);
        String firmaDelOriginal = hmacHex(INBOUND_SECRET, original);
        // Se cambia la cantidad después de firmar: es el ataque que la firma tiene que cazar.
        byte[] alterado = CUERPO_PEDIDO.replace("\"quantity\":1", "\"quantity\":99")
                .getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, alterado, firmaDelOriginal).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Webhook entrante: firma de OTRO secreto → 401")
    void webhookFirmaDeOtroSecreto() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, cuerpo, hmacHex("otro-secreto-distinto", cuerpo)).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Webhook entrante: SIN firma no se acepta (4xx, nunca 2xx)")
    void webhookSinFirma() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        // La cabecera de firma es obligatoria: sin ella Spring corta en el 400, antes del controlador.
        client.post().uri("/api/v1/integrations/shops/" + shopId + "/orders")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    @DisplayName("Webhook entrante: una tienda sin secreto configurado no acepta ninguna entrega (401)")
    void webhookTiendaSinSecreto() {
        UUID shopId = insertShopConnection(null);
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, cuerpo, hmacHex(INBOUND_SECRET, cuerpo)).expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Caso borde: webhook contra una tienda inexistente → 404")
    void webhookTiendaInexistente() {
        byte[] cuerpo = CUERPO_PEDIDO.getBytes(StandardCharsets.UTF_8);

        postWebhook(UUID.randomUUID(), cuerpo, hmacHex(INBOUND_SECRET, cuerpo)).expectStatus().isNotFound();
    }

    @Test
    @DisplayName("Caso borde: con firma válida, un cuerpo que no es un objeto JSON se rechaza con 400")
    void webhookCuerpoNoObjeto() {
        UUID shopId = insertShopConnection(INBOUND_SECRET);
        byte[] cuerpo = "[1,2,3]".getBytes(StandardCharsets.UTF_8);

        postWebhook(shopId, cuerpo, hmacHex(INBOUND_SECRET, cuerpo)).expectStatus().isBadRequest();
    }

    /* ============================================================================================
     * WEBHOOK SALIENTE — firma de las entregas
     * ========================================================================================== */

    @Test
    @DisplayName("Webhook saliente: cada entrega se guarda firmada con el secreto de la suscripción")
    void webhookSalienteVaFirmado() {
        JsonNode sub = crearSuscripcion();
        UUID subId = UUID.fromString(sub.get("id").asText());
        assertThat(sub.get("secret").asText()).isNotBlank();

        client.post().uri(ADMIN_SUBS + "/" + subId + "/test").header(AUTH, bearer(adminToken)).exchange()
                .expectStatus().isOk();

        List<Map<String, Object>> entregas = jdbcTemplate.queryForList(
                "SELECT event_type, signature FROM webhook_delivery WHERE subscription_id = ?", subId);

        assertThat(entregas).hasSize(1);
        assertThat(entregas.get(0).get("event_type")).isEqualTo("test.ping");
        String firma = (String) entregas.get(0).get("signature");
        assertThat(firma).as("HMAC-SHA256 en hexadecimal").hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Webhook saliente: solo la pareja cuerpo+secreto intacta produce la firma que se envía")
    void firmaSalienteSoloValeIntacta() {
        String cuerpo = "{\"id\":\"evt-1\",\"type\":\"order.created\"}";
        String secreto = "secreto-de-la-suscripcion";

        String firma = WebhookDispatcherService.sign(cuerpo, secreto);

        // Determinista: el receptor recalcula lo mismo con el mismo cuerpo y el mismo secreto.
        assertThat(WebhookDispatcherService.sign(cuerpo, secreto)).isEqualTo(firma);
        // Cuerpo alterado: la firma ya no cuadra (es lo que hace que un reenvío manipulado se rechace).
        assertThat(WebhookDispatcherService.sign(cuerpo.replace("evt-1", "evt-2"), secreto)).isNotEqualTo(firma);
        // Secreto equivocado: tampoco (quien no comparte el secreto no puede fabricar entregas).
        assertThat(WebhookDispatcherService.sign(cuerpo, "otro-secreto")).isNotEqualTo(firma);
        assertThat(firma).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Webhook saliente: rotar el secreto cambia la firma del mismo cuerpo")
    void rotarSecretoCambiaLaFirma() {
        JsonNode sub = crearSuscripcion();
        String secretoInicial = sub.get("secret").asText();

        JsonNode rotada = client.post().uri(ADMIN_SUBS + "/" + sub.get("id").asText() + "/rotate-secret")
                .header(AUTH, bearer(adminToken)).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();

        assertThat(rotada).isNotNull();
        String secretoNuevo = rotada.get("secret").asText();
        assertThat(secretoNuevo).isNotEqualTo(secretoInicial);
        String cuerpo = "{\"type\":\"test.ping\"}";
        assertThat(WebhookDispatcherService.sign(cuerpo, secretoNuevo))
                .isNotEqualTo(WebhookDispatcherService.sign(cuerpo, secretoInicial));
    }

    @Test
    @DisplayName("Las suscripciones de webhook son cosa del admin: un usuario normal recibe 403")
    void suscripcionesSoloAdmin() {
        client.get().uri(ADMIN_SUBS).header(AUTH, bearer(userToken)).exchange().expectStatus().isForbidden();
        client.get().uri(ADMIN_SUBS).exchange().expectStatus().isUnauthorized();
    }

    /* ============================================================================================
     * Utilidades
     * ========================================================================================== */

    private JsonNode crearClave(String nombre, List<String> scopes) {
        String cuerpo = "{\"name\":\"" + nombre + "\",\"scopes\":[" + scopes.stream()
                .map(s -> "\"" + s + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]}";
        JsonNode creada = client.post().uri(API_KEYS).header(AUTH, bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange()
                .expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(creada).isNotNull();
        return creada;
    }

    /**
     * Crea una clave con los ámbitos indicados y la CANJEA de verdad en {@code /oauth2/token} por un JWT con
     * los {@code scopesPedidos} (separados por espacios, como manda OAuth2).
     */
    private String tokenDeClave(List<String> scopesDeLaClave, String scopesPedidos) {
        JsonNode clave = crearClave("Clave de prueba " + UUID.randomUUID(), scopesDeLaClave);
        EntityExchangeResult<JsonNode> res = client.post().uri(TOKEN).header(HttpHeaders.AUTHORIZATION, basic(clave))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("grant_type=client_credentials&scope=" + scopesPedidos.replace(" ", "%20"))
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult();
        JsonNode body = res.getResponseBody();
        assertThat(body).as("respuesta de /oauth2/token").isNotNull();
        return body.get("access_token").asText();
    }

    private static String basic(JsonNode clave) {
        String par = clave.get("clientId").asText() + ":" + clave.get("clientSecret").asText();
        return "Basic " + Base64.getEncoder().encodeToString(par.getBytes(StandardCharsets.UTF_8));
    }

    private WebTestClient.ResponseSpec postWebhook(UUID shopId, byte[] cuerpo, String firma) {
        return client.post().uri("/api/v1/integrations/shops/" + shopId + "/orders")
                .contentType(MediaType.APPLICATION_JSON).header(FIRMA, firma).bodyValue(cuerpo).exchange();
    }

    /** HMAC-SHA256 hexadecimal del cuerpo con el secreto compartido — lo que firma la tienda de origen. */
    private static String hmacHex(String secreto, byte[] cuerpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(cuerpo));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el HMAC del test", e);
        }
    }

    private JsonNode crearSuscripcion() {
        JsonNode sub = client.post().uri(ADMIN_SUBS).header(AUTH, bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                // Dominio .invalid: nunca resuelve, así que el reparto asíncrono falla rápido y sin salir a
                // Internet. Lo que se comprueba aquí es la ENTREGA REGISTRADA y su firma, no el destino.
                .bodyValue("{\"name\":\"Suscripción de prueba\",\"targetUrl\":\"https://hooks.invalid/nx036\","
                        + "\"events\":[\"*\"]}")
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(sub).isNotNull();
        return sub;
    }

    private static List<String> campos(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    /** Deja la cuota de la Partner API a cero: el freno de caudal no es lo que miden estos casos. */
    private void limpiarCuotaDePeticiones() {
        rateLimitFilter.reset();
    }

    private void clearAllCaches() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

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

    /** Conexión de tienda con (o sin) secreto de entrada en su metadata — lo que valida el HMAC entrante. */
    private UUID insertShopConnection(String inboundSecret) {
        UUID id = UUID.randomUUID();
        String metadata = inboundSecret == null ? "{}" : "{\"inboundSecret\":\"" + inboundSecret + "\"}";
        jdbcTemplate.update("INSERT INTO user_shop_connection (id, user_id, platform, shop_handle, status, metadata) "
                + "VALUES (?, ?, 'shopify', 'tienda-de-pruebas', 'CONNECTED', CAST(? AS jsonb))",
                id, userId, metadata);
        return id;
    }

    /** Producto visible en el escaparate (y por tanto en la Partner API) para las lecturas de catálogo. */
    private void seedProductoVisible() {
        UUID supplierId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO supplier (id, external_id, source, name, country) "
                + "VALUES (?, 'SUP-PARTNER', '1688', 'Fabrica de pruebas', 'CN')", supplierId);
        UUID categoryId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO category (id, slug, name_zh, position, active, source) "
                + "VALUES (?, 'moda', '时尚', 0, true, '1688')", categoryId);
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO product (id, slug, external_id, source, supplier_id, category_id, title_zh, "
                + "status, base_price, currency, rating, monthly_sales, trend_score, ship_from, free_shipping, "
                + "self_pickup, has_video, inventory_count, moq, shipping_cny, iva_cny) "
                + "VALUES (?, ?, 'EXT-PARTNER', '1688', ?, ?, '冬季外套', 'ACTIVE', ?, 'CNY', 4.5, 20, 5, 'CN', "
                + "false, false, false, 50, 1, 5, 1)",
                productId, SLUG, supplierId, categoryId, new BigDecimal("25.0000"));
        jdbcTemplate.update("INSERT INTO product_image (id, product_id, position, role, source_url, cdn_url) "
                + "VALUES (gen_random_uuid(), ?, 0, 'MAIN', 'https://origen.test/a.jpg', "
                + "'https://cdn.nx036.test/img/a.jpg')", productId);
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                + "VALUES (gen_random_uuid(), ?, 'es', 'Abrigo de invierno', 'Abrigo acolchado')", productId);
        jdbcTemplate.update("INSERT INTO product_translation (id, product_id, language, title, description) "
                + "VALUES (gen_random_uuid(), ?, 'en', 'Winter coat', 'Padded coat')", productId);
    }
}
