package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificación del CARRITO SINCRONIZADO por HTTP contra un Postgres real (Testcontainers).
 *
 * <p>Por qué existe: hasta ahora el carrito activo vivía en {@code localStorage}, de modo que era del
 * DISPOSITIVO y no de la persona — lo añadido en la web no aparecía en la app. Aquí se comprueba lo que
 * de verdad importa de haberlo subido al servidor: que la cesta viaja entre sesiones, que la identidad de
 * una línea es (producto, variante), que fundir no pierde unidades, que el MOQ es un suelo… y, sobre
 * todo, que <b>nadie puede tocar la cesta de otro</b>. Esa última parte no es teórica: en este proyecto ya
 * hubo IDOR reales (pago, Academy, partner), así que aquí queda fijada con pruebas.
 */
@DisplayName("Carrito sincronizado · la cesta sigue a la persona, no al dispositivo")
class CartSyncFlowIT extends BaseIntegration {

    private static final String CART = "/api/me/cart";
    private static final String CART_MERGE = "/api/me/cart/merge";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LINEAS = new ParameterizedTypeReference<>() {
    };

    private UUID duenoId;
    /** Dos tokens del MISMO usuario: la web y la app, cada una con su sesión. */
    private String tokenWeb;
    private String tokenApp;
    private String tokenIntruso;
    private UUID productoId;
    private UUID varianteRoja;
    private UUID varianteAzul;

    @BeforeEach
    void prepararEscenario() {
        duenoId = crearUsuario("dueno-" + UUID.randomUUID() + "@example.com");
        tokenWeb = jwt.userToken(duenoId, "dueno@example.com", "USER");
        tokenApp = jwt.userToken(duenoId, "dueno@example.com", "USER");
        tokenIntruso = jwt.userToken(crearUsuario("intruso-" + UUID.randomUUID() + "@example.com"),
                "intruso@example.com", "USER");
        productoId = crearProducto(1);
        varianteRoja = crearVariante(productoId, "ROJA");
        varianteAzul = crearVariante(productoId, "AZUL");
    }

    /* ============================== La cesta sigue a la persona ============================== */

    /**
     * El motivo de toda la funcionalidad: lo añadido desde la web tiene que aparecer en la app sin que
     * nadie sincronice nada a mano.
     */
    @Test
    @DisplayName("lo añadido en la web aparece en la app: misma cuenta, otra sesión")
    void loAnadidoEnLaWebApareceEnLaApp() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 2));

        List<Map<String, Object>> enLaApp = listar(tokenApp);

        assertThat(enLaApp).hasSize(1);
        assertThat(enLaApp.get(0)).containsEntry("productId", productoId.toString())
                .containsEntry("variantId", varianteRoja.toString()).containsEntry("quantity", 2)
                .containsEntry("title", "Camisa");
    }

    /**
     * PUT FIJA la cantidad, no la suma. Es lo que permite bajar de 5 a 2 desde la pantalla del carrito y
     * lo que hace que un reintento por red inestable —lo normal en móvil— no duplique el pedido.
     */
    @Test
    @DisplayName("guardar dos veces la misma línea fija la cantidad, no la duplica")
    void guardarDosVecesFijaLaCantidad() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 5));
        List<Map<String, Object>> tras = guardar(tokenApp, linea(productoId, varianteRoja, 2));

        assertThat(tras).hasSize(1);
        assertThat(tras.get(0)).containsEntry("quantity", 2);
    }

    @Test
    @DisplayName("dos variantes del mismo producto son dos líneas distintas")
    void dosVariantesSonDosLineas() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 1));
        guardar(tokenWeb, linea(productoId, varianteAzul, 3));

        List<Map<String, Object>> cesta = listar(tokenWeb);

        assertThat(cesta).hasSize(2);
        assertThat(cesta).extracting(l -> l.get("variantId")).containsExactlyInAnyOrder(varianteRoja.toString(),
                varianteAzul.toString());
        assertThat(cantidadDe(cesta, varianteAzul)).isEqualTo(3);
    }

    /** El producto base (sin variante) es una línea más, y no colisiona con las de sus variantes. */
    @Test
    @DisplayName("el producto sin variante convive con sus variantes como una línea propia")
    void elProductoBaseConviveConSusVariantes() {
        guardar(tokenWeb, linea(productoId, null, 1));
        guardar(tokenWeb, linea(productoId, varianteRoja, 1));
        guardar(tokenWeb, linea(productoId, null, 4));

        List<Map<String, Object>> cesta = listar(tokenWeb);

        assertThat(cesta).hasSize(2);
        assertThat(cantidadDe(cesta, null)).as("la línea sin variante se actualizó, no se duplicó").isEqualTo(4);
    }

    /* ============================== Fusión al iniciar sesión ============================== */

    /**
     * Quien llenó la cesta sin sesión y luego entra no puede perder unidades por ninguno de los dos lados:
     * 3 en la cuenta + 2 del navegador = 5, y lo que solo estaba en local se añade.
     */
    @Test
    @DisplayName("al fundir, las cantidades de la misma línea se SUMAN")
    void alFundirLasCantidadesSeSuman() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 3));

        List<Map<String, Object>> tras = fundir(tokenApp,
                List.of(linea(productoId, varianteRoja, 2), linea(productoId, varianteAzul, 1)));

        assertThat(tras).hasSize(2);
        assertThat(cantidadDe(tras, varianteRoja)).as("3 de la cuenta + 2 del navegador").isEqualTo(5);
        assertThat(cantidadDe(tras, varianteAzul)).as("lo que solo estaba en local se añade").isEqualTo(1);
    }

    @Test
    @DisplayName("fundir una lista vacía deja la cesta como estaba")
    void fundirUnaListaVaciaNoCambiaNada() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 2));

        assertThat(fundir(tokenApp, List.of())).hasSize(1);
    }

    /* ============================== Reglas de negocio ============================== */

    /** Por debajo del MOQ el proveedor no sirve el pedido: el mínimo manda sobre lo que pida el cliente. */
    @Test
    @DisplayName("la cantidad nunca baja del MOQ del producto")
    void laCantidadNuncaBajaDelMoq() {
        UUID conMinimo = crearProducto(10);

        List<Map<String, Object>> tras = guardar(tokenWeb, linea(conMinimo, null, 4));

        assertThat(tras.get(0)).containsEntry("quantity", 10).containsEntry("moq", 10);
    }

    @Test
    @DisplayName("una cantidad de cero se rechaza (400) y no crea línea")
    void unaCantidadDeCeroSeRechaza() {
        guardarRechazado(tokenWeb, linea(productoId, varianteRoja, 0), 400);

        assertThat(listar(tokenWeb)).isEmpty();
    }

    @Test
    @DisplayName("un producto que no existe en el catálogo se rechaza (404)")
    void unProductoInexistenteSeRechaza() {
        guardarRechazado(tokenWeb, linea(UUID.randomUUID(), null, 1), 404);

        assertThat(listar(tokenWeb)).isEmpty();
    }

    /* ============================== Quitar y vaciar ============================== */

    @Test
    @DisplayName("quitar una línea con variante deja intactas las demás")
    void quitarUnaLineaDejaLasDemas() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 1));
        guardar(tokenWeb, linea(productoId, varianteAzul, 2));

        List<Map<String, Object>> tras = quitar(tokenApp, productoId, varianteRoja);

        assertThat(tras).hasSize(1);
        assertThat(tras.get(0)).containsEntry("variantId", varianteAzul.toString());
    }

    /** Lo necesita el cierre del pedido: al cobrar, la cesta se vacía en todos los dispositivos a la vez. */
    @Test
    @DisplayName("vaciar la cesta la deja vacía también en la otra sesión")
    void vaciarLaCestaLaDejaVaciaEnTodasPartes() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 1));
        guardar(tokenWeb, linea(productoId, varianteAzul, 2));

        assertThat(vaciar(tokenWeb)).isEmpty();
        assertThat(listar(tokenApp)).isEmpty();
        assertThat(lineasEnBd(duenoId)).isZero();
    }

    /* ============================== Aislamiento entre cuentas (IDOR) ============================== */

    /**
     * La prueba que fija el aislamiento. El identificador de usuario sale SIEMPRE de la autenticación, así
     * que un segundo cliente —autenticado, pero otro— no puede leer, modificar, quitar ni vaciar la cesta
     * del primero. Cada aserción comprueba además que la cesta del dueño queda EXACTAMENTE como estaba.
     */
    @Test
    @DisplayName("un usuario no puede leer, tocar ni vaciar la cesta de otro")
    void unUsuarioNoPuedeAfectarLaCestaDeOtro() {
        guardar(tokenWeb, linea(productoId, varianteRoja, 4));

        assertThat(listar(tokenIntruso)).as("la cesta ajena no se lee: cada uno ve la suya").isEmpty();

        assertThat(quitar(tokenIntruso, productoId, varianteRoja))
                .as("quitar solo alcanza a la propia cesta (la del intruso, vacía)").isEmpty();
        assertThat(vaciar(tokenIntruso)).as("vaciar tampoco cruza de cuenta").isEmpty();

        // Y el intruso guardando la MISMA línea se la guarda en SU cesta, sin pisar la del dueño.
        assertThat(guardar(tokenIntruso, linea(productoId, varianteRoja, 99))).hasSize(1);

        List<Map<String, Object>> delDueno = listar(tokenWeb);
        assertThat(delDueno).as("la cesta del dueño sigue intacta tras todo lo anterior").hasSize(1);
        assertThat(delDueno.get(0)).containsEntry("quantity", 4);
        assertThat(lineasEnBd(duenoId)).isEqualTo(1);
    }

    @Test
    @DisplayName("sin autenticación no hay carrito: 401 en todas las operaciones")
    void sinAutenticacionNoHayCarrito() {
        client.get().uri(CART).exchange().expectStatus().isUnauthorized();
        client.put().uri(CART).contentType(MediaType.APPLICATION_JSON).bodyValue(linea(productoId, varianteRoja, 1))
                .exchange().expectStatus().isUnauthorized();
        client.post().uri(CART_MERGE).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(linea(productoId, varianteRoja, 1))).exchange().expectStatus().isUnauthorized();
        client.delete().uri(CART + "/" + productoId).exchange().expectStatus().isUnauthorized();
        client.delete().uri(CART).exchange().expectStatus().isUnauthorized();
    }

    /* ============================== Utilidades ============================== */

    private List<Map<String, Object>> listar(String token) {
        return client.get().uri(CART).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange().expectStatus().isOk()
                .expectBody(LINEAS).returnResult().getResponseBody();
    }

    /** Guarda una línea y devuelve la cesta resultante (el caso bueno: 200). */
    private List<Map<String, Object>> guardar(String token, Map<String, Object> linea) {
        return client.put().uri(CART).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(linea).exchange().expectStatus().isOk()
                .expectBody(LINEAS).returnResult().getResponseBody();
    }

    /**
     * Guardado que debe FALLAR: solo se comprueba el estado. El cuerpo de un error es el sobre de error
     * de la aplicación, no una lista de líneas, así que intentar leerlo como lista rompería el test por el
     * motivo equivocado.
     */
    private void guardarRechazado(String token, Map<String, Object> linea, int estadoEsperado) {
        client.put().uri(CART).header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(linea).exchange().expectStatus().isEqualTo(estadoEsperado);
    }

    private List<Map<String, Object>> fundir(String token, List<Map<String, Object>> lineas) {
        return client.post().uri(CART_MERGE).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(lineas).exchange().expectStatus().isOk()
                .expectBody(LINEAS).returnResult().getResponseBody();
    }

    private List<Map<String, Object>> quitar(String token, UUID producto, UUID variante) {
        String uri = CART + "/" + producto + (variante == null ? "" : "?variantId=" + variante);
        return client.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange().expectStatus()
                .isOk().expectBody(LINEAS).returnResult().getResponseBody();
    }

    private List<Map<String, Object>> vaciar(String token) {
        return client.delete().uri(CART).header(HttpHeaders.AUTHORIZATION, bearer(token)).exchange().expectStatus()
                .isOk().expectBody(LINEAS).returnResult().getResponseBody();
    }

    /** Cantidad de la línea de esa variante ({@code null} = producto base), o 0 si no está en la cesta. */
    private int cantidadDe(List<Map<String, Object>> cesta, UUID variante) {
        String buscada = variante == null ? null : variante.toString();
        return cesta.stream().filter(l -> Objects.equals(l.get("variantId"), buscada))
                .map(l -> (Integer) l.get("quantity")).findFirst().orElse(0);
    }

    /** Cuerpo de una línea tal como lo manda el cliente: sin ningún identificador de usuario. */
    private Map<String, Object> linea(UUID producto, UUID variante, int cantidad) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", producto.toString());
        body.put("variantId", variante == null ? null : variante.toString());
        body.put("sku", "SKU-" + (variante == null ? "BASE" : variante.toString().substring(0, 8)));
        body.put("slug", "camisa");
        body.put("title", "Camisa");
        body.put("image", "https://cdn.local/camisa.jpg");
        body.put("variantLabel", variante == null ? null : "Color");
        body.put("unitPriceSource", new BigDecimal("10.00"));
        body.put("sourceCurrency", "EUR");
        body.put("quantity", cantidad);
        // MOQ "de pintado" mandado por el cliente: el backend lo ignora y devuelve el del catálogo.
        body.put("moq", 1);
        body.put("unitPriceDisplay", new BigDecimal("12.00"));
        body.put("displayCurrency", "EUR");
        body.put("displaySymbol", "€");
        return body;
    }

    private UUID crearUsuario(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, role, active, created_at, updated_at)"
                + " VALUES (?, ?, 'USER', true, now(), now())", id, email);
        return id;
    }

    private UUID crearProducto(int moq) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO product (id, slug, external_id, source, title_zh, status, moq,"
                        + " base_price, currency, weight_grams, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'TEST', '衬衫', 'ACTIVE', ?, 10.0000, 'EUR', 500, now(), now())",
                id, "camisa-" + sufijo, "ext-" + sufijo, moq);
        return id;
    }

    private UUID crearVariante(UUID producto, String color) {
        UUID id = UUID.randomUUID();
        String sufijo = id.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO product_variant (id, product_id, external_id, sku, title, price,"
                        + " stock, active, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 10.0000, 100, true, now(), now())",
                id, producto, "var-" + sufijo, "SKU-" + sufijo, color);
        return id;
    }

    private int lineasEnBd(UUID userId) {
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM cart_item WHERE user_id = ?", Integer.class,
                userId);
        return total == null ? 0 : total;
    }
}
