package com.nexaplatform.dropshipping.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CERTIFICACIÓN DE EXTREMO A EXTREMO — recorrido COMPLETO de un administrador.
 *
 * <p>Igual que el recorrido del cliente, es UNA cadena: el producto que se da de alta es el que se
 * importa en lote, el que se pausa y se reactiva, el que compra un cliente, el que recorre los estados
 * del pedido hasta entregarse (acreditando la comisión del operador) y el que se acaba reembolsando con
 * el dinero de vuelta al céntimo. Va en un {@link TestFactory} porque {@code BaseIntegration} vacía la
 * base antes de CADA método de test: repartir el recorrido en varios {@code @Test} perdería el estado.
 *
 * <p><b>Autorización.</b> Toda operación sensible se comprueba también con un usuario NORMAL (403) y sin
 * token (401). No hay ni una sola anotación {@code @PreAuthorize} en la aplicación: la autorización es
 * por prefijo de ruta en {@code BffSecurityConfig}, con una trampa que esta certificación fija por
 * escrito — {@code /api/admin/operator/**} (singular) lo abre también el OPERADOR, mientras que
 * {@code /api/admin/operators/**} (plural) es solo del administrador.
 *
 * <p><b>Dinero.</b> Todo importe se comprueba EXACTO. El escenario usa 8 CNY por dólar y un margen
 * global del 150 %: 80 CNY de coste → 10,00 $ → 25,00 $ con margen → 32,50 $ con el IVA y el envío del
 * producto, que desde el 25-ago-2026 también llevan margen (el margen grava el desembolso COMPLETO al
 * proveedor: 25,00 + 3,00 × 2,5). Los casos borde (cero, uno, el máximo, el máximo + 1, vacíos, nulos, repeticiones, estados
 * imposibles y recursos ajenos) van dentro de cada bloque, no en un apartado suelto.
 */
@DisplayName("Certificación e2e · recorrido completo del administrador")
class AdminJourneyIT extends BaseIntegration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * IP propia y aleatoria: el limitador de tráfico guarda sus cubos en memoria para toda la JVM y
     * limita las altas y los accesos por IP. Con una IP propia este recorrido no compite por la cuota
     * con ninguna otra clase de la misma ejecución.
     */
    private static final String IP_PROPIA = "10." + ThreadLocalRandom.current().nextInt(1, 250) + "."
            + ThreadLocalRandom.current().nextInt(1, 250) + "." + ThreadLocalRandom.current().nextInt(1, 250);

    private static final String CLAVE_ADMIN = "CertAdmin123!";
    private static final String SLUG_CATEGORIA = "cert-recorrido-admin";
    private static final String SLUG_CATEGORIA_VACIA = "cert-categoria-vacia";

    /* ── Importes del escenario (céntimos USD), calculados a mano ───────────────────────────────── */

    /**
     * Precio unitario de venta: 25,00 de base con margen + 2,50 de IVA + 5,00 de envío del producto.
     *
     * <p>El IVA y el porte del proveedor son 1,00 y 2,00 en origen y llevan el MISMO factor de margen
     * que el coste (2,5) desde el 25-ago-2026: el margen grava el desembolso completo al proveedor.
     */
    private static final int UNIDAD_CENTIMOS = 3250;
    private static final int ENVIO_ES_CENTIMOS = 849;   // 4,99 fijos + 3,50 × 1 kg
    private static final int DESPACHO_ES_CENTIMOS = 250;
    private static final int IVA_ES_BPS = 2100;
    private static final long SALDO_INICIAL = 20000L;
    /** 6.500 (producto) + 849 (porte) + 250 (despacho) + 1.543 (IVA sobre 7.349) = 9.142. */
    private static final long TOTAL_PEDIDO = 9142L;
    /** Comisión del operador al entregar: (8.000 × 2 / 1,13) × 10 % = 1.415,9292 → 1.416 fen. */
    private static final long COMISION_CNY_CENTIMOS = 1416L;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /* ── Estado del recorrido ───────────────────────────────────────────────────────────────────── */

    /**
     * Modo diagnóstico. En false (lo normal) un paso roto OMITE los siguientes: el informe señala el
     * punto exacto donde se rompió la cadena en vez de sepultarlo bajo fallos derivados. Ponerlo a true
     * ejecuta el recorrido entero pase lo que pase, que es lo útil cuando se está depurando y se quiere
     * ver todos los puntos rotos de una sola pasada.
     */
    private static final boolean SEGUIR_TRAS_FALLO = false;

    private boolean cadenaRota;
    private String emailAdmin;
    private UUID idAdmin;
    private String tokenAdmin;
    private String tokenUsuarioNormal;
    private UUID idCliente;
    private String tokenCliente;
    private UUID idOperador;
    private String tokenOperador;
    private UUID idProducto;
    private UUID idVariante;
    private UUID idCategoria;
    private UUID idCategoriaVacia;
    private UUID idReglaProducto;
    private UUID idPromocion;
    private UUID idPedido;
    private UUID idUsuarioGestionado;
    private String slugProducto;

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  EL RECORRIDO
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    @TestFactory
    @DisplayName("El administrador gobierna la plataforma de principio a fin")
    List<DynamicTest> recorridoDelAdministrador() {
        prepararPlataforma();

        List<DynamicTest> pasos = new ArrayList<>();
        pasos.addAll(bloqueAcceso());
        pasos.addAll(bloqueProducto());
        pasos.addAll(bloqueImportacionMasiva());
        pasos.addAll(bloqueActivarDesactivar());
        pasos.addAll(bloqueCategorias());
        pasos.addAll(bloquePreciosYMargenes());
        pasos.addAll(bloquePromociones());
        pasos.addAll(bloquePedidoDelCliente());
        pasos.addAll(bloqueOperadores());
        pasos.addAll(bloqueUsuarios());
        pasos.addAll(bloqueMonedas());
        pasos.addAll(bloqueAduanas());
        pasos.addAll(bloqueMetricas());
        pasos.addAll(bloqueAutorizacionCruzada());
        return pasos;
    }

    /* ── 1. Acceso ──────────────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueAcceso() {
        return List.of(
                paso("Entra con sus credenciales y recibe un token con el rol ADMIN", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailAdmin, "password", CLAVE_ADMIN));
                    assertThat(r.status()).isEqualTo(200);
                    tokenAdmin = r.cuerpo().get("token").asText();
                    assertThat(r.cuerpo().get("user").get("role").asText()).isEqualTo("ADMIN");
                }),

                paso("Con la contraseña equivocada no entra", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/auth/login", null,
                                Map.of("email", emailAdmin, "password", "NoEsLaMia1!")).status()).isEqualTo(401)),

                paso("El panel de administración exige token: sin él responde 401", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/admin/dashboard/metrics", null, null).status())
                                .isEqualTo(401)),

                paso("Un usuario NORMAL no entra al panel: 403", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/admin/dashboard/metrics", tokenUsuarioNormal, null)
                                .status()).isEqualTo(403)));
    }

    /* ── 2. Alta y edición de producto ──────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueProducto() {
        return List.of(
                paso("Da de alta el producto y queda tarificado a 32,50 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin,
                            producto("Camiseta de certificación", 80.0, 1));
                    assertThat(r.status()).isEqualTo(200);
                    idProducto = UUID.fromString(r.cuerpo().asText());

                    // El espejado de imágenes necesita S3/MinIO, que no existe en la certificación; el
                    // escaparate solo lista lo que tiene imagen espejada. Se marca a mano: preparación,
                    // no comprobación.
                    jdbcTemplate.update("UPDATE product_image SET cdn_url = source_url WHERE cdn_url IS NULL");
                    vaciarCaches();

                    Respuesta detalle = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    assertThat(detalle.status()).isEqualTo(200);
                    slugProducto = detalle.cuerpo().get("slug").asText();
                    assertThat(detalle.cuerpo().get("title").asText()).isEqualTo("Camiseta de certificación");
                    importeExacto("precio de venta del alta", detalle.cuerpo().get("retailUsd"), "32.50");
                    assertThat(detalle.cuerpo().get("variants")).hasSize(1);
                    idVariante = UUID.fromString(detalle.cuerpo().get("variants").get(0).get("id").asText());
                    // El administrador SÍ ve el desglose: 25,00 de base, 2,50 de IVA y 5,00 de envío. El IVA
                    // y el porte del proveedor —1,00 y 2,00 sin margen— llevan el mismo factor 2,5 que el
                    // coste desde el 25-ago-2026, y las tres cifras suman los 32,50 que se cobran.
                    assertThat(detalle.cuerpo().get("baseFormatted").asText()).isEqualTo("$25.00");
                    assertThat(detalle.cuerpo().get("ivaFormatted").asText()).isEqualTo("$2.50");
                    assertThat(detalle.cuerpo().get("shippingFormatted").asText()).isEqualTo("$5.00");
                }),

                paso("Un producto SIN título se rechaza y no deja nada a medias", () -> {
                    // El mensaje que sale al cliente es el genérico del catálogo de errores (BR001); el
                    // motivo concreto queda en el registro. Por eso se certifica el CÓDIGO y, sobre todo,
                    // que no se ha creado ningún producto.
                    int antes = enteroEnBd("SELECT count(*) FROM product");
                    Map<String, Object> sinTitulo = producto(null, 80.0, 1);
                    sinTitulo.remove("titleEs");
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin,
                            sinTitulo);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("BR001");
                    assertThat(enteroEnBd("SELECT count(*) FROM product")).isEqualTo(antes);
                }),

                paso("Un producto SIN precio se rechaza", () -> {
                    int antes = enteroEnBd("SELECT count(*) FROM product");
                    Map<String, Object> sinPrecio = producto("Sin precio", 80.0, 1);
                    sinPrecio.remove("price");
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin,
                            sinPrecio);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM product")).isEqualTo(antes);
                }),

                paso("Un producto SIN envío ni IVA se rechaza: el precio no se podría componer", () -> {
                    Map<String, Object> sinEnvio = producto("Sin envío", 80.0, 1);
                    sinEnvio.remove("shippingCny");
                    assertThat(llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin, sinEnvio)
                            .status()).isEqualTo(422);
                    Map<String, Object> sinIva = producto("Sin IVA", 80.0, 1);
                    sinIva.remove("ivaCny");
                    assertThat(llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin, sinIva)
                            .status()).isEqualTo(422);
                }),

                paso("Un producto SIN imágenes se rechaza: el escaparate no podría enseñarlo", () -> {
                    int antes = enteroEnBd("SELECT count(*) FROM product");
                    Map<String, Object> sinImagen = producto("Sin imagen", 80.0, 1);
                    sinImagen.remove("imageUrls");
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin,
                            sinImagen);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM product")).isEqualTo(antes);
                }),

                paso("Edita el producto: título y marca, sin tocar el precio", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                            tokenAdmin, Map.of("title", "Camiseta de certificación (editada)",
                                    "brand", "NX036"));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("title").asText()).isEqualTo("Camiseta de certificación (editada)");
                    assertThat(r.cuerpo().get("brand").asText()).isEqualTo("NX036");
                    assertThat(r.cuerpo().get("moq").asInt()).as("lo no enviado no se toca").isEqualTo(1);
                    importeExacto("el precio no cambia al editar textos", r.cuerpo().get("retailUsd"), "32.50");
                }),

                paso("Editar un producto inexistente responde 404", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + UUID.randomUUID(),
                                tokenAdmin, Map.of("title", "Fantasma")).status()).isEqualTo(404)),

                paso("Cambia el precio del proveedor a 160 CNY y la venta pasa a 53,00 $", () -> {
                    // 160/8 = 20 de coste → ×2,5 = 50 de base → +1 IVA +2 envío = 53,00. Al céntimo.
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                            tokenAdmin, Map.of("basePrice", 160.0));
                    assertThat(r.status()).isEqualTo(200);
                    vaciarCaches();
                    Respuesta detalle = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    // La variante sigue a 80 CNY y es la que manda en el precio de cabecera: 32,50.
                    importeExacto("manda la variante comprable, no el precio base", detalle.cuerpo().get("retailUsd"),
                            "32.50");
                }),

                paso("Devuelve el precio base a 80 CNY para el resto del recorrido", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                            tokenAdmin, Map.of("basePrice", 80.0)).status()).isEqualTo(200);
                    vaciarCaches();
                }));
    }

    /* ── 3. Importación masiva ──────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueImportacionMasiva() {
        return List.of(
                paso("Importa DOS productos en lote y los dos entran", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/bulk", tokenAdmin,
                            List.of(producto("Gorra de certificación", 40.0, 1),
                                    producto("Bufanda de certificación", 24.0, 1)));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("created").asInt()).isEqualTo(2);
                    assertThat(r.cuerpo().get("failed").asInt()).isZero();
                    assertThat(r.cuerpo().get("errors")).isEmpty();
                    assertThat(enteroEnBd("SELECT count(*) FROM product")).isEqualTo(3);
                }),

                paso("Reimportar el MISMO lote actualiza en el sitio y no duplica nada", () -> {
                    // La importación es un UPSERT por identificador externo: repetirla es idempotente.
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/bulk", tokenAdmin,
                            List.of(producto("Gorra de certificación", 40.0, 1),
                                    producto("Bufanda de certificación", 24.0, 1)));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("failed").asInt()).isZero();
                    assertThat(enteroEnBd("SELECT count(*) FROM product"))
                            .as("siguen siendo tres productos").isEqualTo(3);
                }),

                paso("Un lote con una fila rota importa las buenas y detalla la que falla", () -> {
                    Map<String, Object> rota = producto("Rota", 10.0, 1);
                    rota.remove("imageUrls");
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/bulk", tokenAdmin,
                            List.of(producto("Calcetines de certificación", 16.0, 1), rota));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("created").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("failed").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("errors")).hasSize(1);
                    assertThat(r.cuerpo().get("errors").get(0).asText())
                            .as("el error dice QUÉ fila y por qué").startsWith("Fila 2:");
                }),

                paso("Un lote VACÍO no importa nada y tampoco falla", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/products/bulk", tokenAdmin, List.of());
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("created").asInt()).isZero();
                    assertThat(r.cuerpo().get("failed").asInt()).isZero();
                }),

                paso("Importa por líneas (NDJSON), el formato de los volcados grandes", () -> {
                    String ndjson = escribirJson(producto("Cinturón de certificación", 32.0, 1)) + "\n"
                            + escribirJson(producto("Guantes de certificación", 20.0, 1)) + "\n";
                    Respuesta r = llamarTexto(HttpMethod.POST, "/api/admin/catalog/products/import/ndjson",
                            tokenAdmin, ndjson, "application/x-ndjson");
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("created").asInt()).isEqualTo(2);
                    assertThat(r.cuerpo().get("failed").asInt()).isZero();
                }),

                paso("Una línea NDJSON que no es JSON se anota como error de lectura", () -> {
                    Respuesta r = llamarTexto(HttpMethod.POST, "/api/admin/catalog/products/import/ndjson",
                            tokenAdmin, "esto-no-es-json\n", "application/x-ndjson");
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("created").asInt()).isZero();
                    assertThat(r.cuerpo().get("failed").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("errors").get(0).asText()).contains("parse: ");
                }),

                paso("El listado de administración pagina el catálogo importado", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/catalog/products?page=0&size=3&lang=es",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("items")).hasSize(3);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(6);
                    assertThat(r.cuerpo().get("totalPages").asInt()).isEqualTo(2);
                }));
    }

    /* ── 4. Activar y desactivar ────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueActivarDesactivar() {
        return List.of(
                paso("Pausa el producto y desaparece del listado del escaparate al instante", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "/status",
                            tokenAdmin, Map.of("status", "PAUSED")).status()).isEqualTo(204);
                    assertThat(textoEnBd("SELECT status FROM product WHERE id = ?", idProducto))
                            .isEqualTo("PAUSED");
                    Respuesta listado = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&size=100",
                            tokenCliente, null);
                    List<String> visibles = new ArrayList<>();
                    listado.cuerpo().get("items").forEach(n -> visibles.add(n.get("id").asText()));
                    assertThat(visibles).as("el pausado ya no se ofrece en el catálogo")
                            .doesNotContain(idProducto.toString());
                }),

                paso("La ficha del producto pausado deja de servirse por enlace directo", () -> {
                    // El listado ya lo ocultaba y el cobro ya lo rechazaba, pero getProductBySlug no filtraba
                    // por estado: un enlace directo (o un resultado indexado) seguía enseñando la ficha
                    // completa —con precio— de un producto retirado. Ahora es 404 para quien no es admin.
                    Respuesta ficha = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    assertThat(ficha.status()).as("un producto retirado no existe para el escaparate")
                            .isEqualTo(404);
                    // El admin sí la abre: desde el panel se revisa y se reactiva justo lo que está pausado.
                    Respuesta paraAdmin = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", tokenAdmin, null);
                    assertThat(paraAdmin.status()).isEqualTo(200);
                    assertThat(paraAdmin.cuerpo().get("status").asText()).isEqualTo("PAUSED");
                }),

                paso("Y comprar un producto pausado sí se rechaza, con el motivo identificable", () -> {
                    Map<String, Object> compra = new LinkedHashMap<>();
                    compra.put("shippingAddressInline", Map.of("fullName", "Cliente Cert", "line1", "Calle 1",
                            "city", "Madrid", "postalCode", "28001", "country", "ES"));
                    compra.put("items", List.of(linea(idProducto, idVariante, 1)));
                    compra.put("paymentMethod", "WALLET");
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente, compra);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("PRODUCT_UNAVAILABLE");
                    assertThat(saldoCliente()).as("no se cobra nada").isEqualTo(SALDO_INICIAL);
                }),

                paso("Un estado inventado se rechaza y el producto se queda como estaba", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "/status",
                            tokenAdmin, Map.of("status", "LO-QUE-SEA"));
                    assertThat(r.status()).isEqualTo(400);
                    assertThat(textoEnBd("SELECT status FROM product WHERE id = ?", idProducto))
                            .isEqualTo("PAUSED");
                }),

                paso("Un estado VACÍO se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "/status",
                                tokenAdmin, Map.of("status", "")).status()).isEqualTo(400)),

                paso("Lo reactiva y vuelve a estar en el catálogo", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "/status",
                            tokenAdmin, Map.of("status", "ACTIVE")).status()).isEqualTo(204);
                    Respuesta listado = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&size=100",
                            tokenCliente, null);
                    List<String> visibles = new ArrayList<>();
                    listado.cuerpo().get("items").forEach(n -> visibles.add(n.get("id").asText()));
                    assertThat(visibles).contains(idProducto.toString());
                }),

                paso("Repetir el mismo estado es idempotente", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "/status",
                            tokenAdmin, Map.of("status", "ACTIVE")).status()).isEqualTo(204);
                    assertThat(textoEnBd("SELECT status FROM product WHERE id = ?", idProducto))
                            .isEqualTo("ACTIVE");
                }),

                paso("Pausa y reactiva EN LOTE, contando exactamente cuántos cambiaron", () -> {
                    List<String> ids = jdbcTemplate.queryForList("SELECT id::text FROM product", String.class);
                    Respuesta pausa = llamar(HttpMethod.PUT, "/api/admin/catalog/products/bulk-status", tokenAdmin,
                            Map.of("ids", ids, "status", "PAUSED"));
                    assertThat(pausa.status()).isEqualTo(200);
                    assertThat(pausa.cuerpo().get("succeeded").asInt()).isEqualTo(6);
                    assertThat(enteroEnBd("SELECT count(*) FROM product WHERE status = 'ACTIVE'")).isZero();

                    Respuesta alta = llamar(HttpMethod.PUT, "/api/admin/catalog/products/bulk-status", tokenAdmin,
                            Map.of("ids", ids, "status", "ACTIVE"));
                    assertThat(alta.cuerpo().get("succeeded").asInt()).isEqualTo(6);
                    assertThat(enteroEnBd("SELECT count(*) FROM product WHERE status = 'ACTIVE'")).isEqualTo(6);
                    vaciarCaches();
                }),

                paso("Un lote con un identificador inexistente lo cuenta como fallo sin tumbar el resto", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/products/bulk-status", tokenAdmin,
                            Map.of("ids", List.of(idProducto.toString(), UUID.randomUUID().toString()),
                                    "status", "ACTIVE"));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("succeeded").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("failed").asInt()).isEqualTo(1);
                }));
    }

    /* ── 5. Categorías ──────────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueCategorias() {
        return List.of(
                paso("Crea una categoría nueva y vacía", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/categories", tokenAdmin,
                            Map.of("slug", SLUG_CATEGORIA_VACIA, "nameZh", "空", "position", 1,
                                    "nameTranslations", Map.of("es", "Vacía")));
                    assertThat(r.status()).isEqualTo(200);
                    idCategoriaVacia = UUID.fromString(r.cuerpo().asText());
                }),

                paso("Repetir el mismo identificador de categoría se rechaza y no la duplica", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/catalog/categories", tokenAdmin,
                            Map.of("slug", SLUG_CATEGORIA_VACIA, "nameZh", "空"));
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM category WHERE slug = ?", SLUG_CATEGORIA_VACIA))
                            .isEqualTo(1);
                }),

                paso("Una categoría sin identificador se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/catalog/categories", tokenAdmin,
                                Map.of("slug", "", "nameZh", "空")).status()).isEqualTo(400)),

                paso("El listado cuenta los productos que cuelgan de cada categoría", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/catalog/categories", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    JsonNode conProductos = buscar(r.cuerpo(), "slug", SLUG_CATEGORIA);
                    assertThat(conProductos.get("productCount").asInt()).isEqualTo(6);
                    JsonNode vacia = buscar(r.cuerpo(), "slug", SLUG_CATEGORIA_VACIA);
                    assertThat(vacia.get("productCount").asInt()).isZero();
                }),

                paso("Renombra la categoría manteniendo su identificador", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/categories/" + idCategoriaVacia,
                            tokenAdmin, Map.of("slug", SLUG_CATEGORIA_VACIA, "nameZh", "空空", "active", true,
                                    "names", Map.of("es", "Vacía renombrada")));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("names").get("es").asText()).isEqualTo("Vacía renombrada");
                }),

                paso("Un identificador con mayúsculas o espacios se rechaza por el patrón", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/categories/" + idCategoriaVacia,
                                tokenAdmin, Map.of("slug", "Slug Invalido", "nameZh", "空")).status())
                                .isEqualTo(400)),

                paso("Renombrarla al identificador de otra categoría se rechaza", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/categories/" + idCategoriaVacia,
                            tokenAdmin, Map.of("slug", SLUG_CATEGORIA, "nameZh", "空"));
                    assertThat(r.status()).isEqualTo(422);
                }),

                paso("La apaga y la enciende con el interruptor", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/categories/" + idCategoriaVacia
                            + "/toggle", tokenAdmin, null).cuerpo().get("active").asBoolean()).isFalse();
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/categories/" + idCategoriaVacia
                            + "/toggle", tokenAdmin, null).cuerpo().get("active").asBoolean()).isTrue();
                }),

                paso("Activa y desactiva categorías EN LOTE", () -> {
                    Respuesta apagar = llamar(HttpMethod.PUT, "/api/admin/catalog/categories/bulk-active",
                            tokenAdmin, Map.of("ids", List.of(idCategoria.toString(),
                                    idCategoriaVacia.toString()), "active", false));
                    assertThat(apagar.status()).isEqualTo(200);
                    assertThat(apagar.cuerpo().get("updated").asInt()).isEqualTo(2);
                    Respuesta encender = llamar(HttpMethod.PUT, "/api/admin/catalog/categories/bulk-active",
                            tokenAdmin, Map.of("ids", List.of(idCategoria.toString(),
                                    idCategoriaVacia.toString()), "active", true));
                    assertThat(encender.cuerpo().get("updated").asInt()).isEqualTo(2);
                }),

                paso("Un lote de categorías con identificadores inexistentes no cambia nada", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/catalog/categories/bulk-active", tokenAdmin,
                            Map.of("ids", List.of(UUID.randomUUID().toString()), "active", false));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("updated").asInt()).isZero();
                }),

                paso("Borrar una categoría CON productos se rechaza y no deja productos huérfanos", () -> {
                    Respuesta r = llamar(HttpMethod.DELETE, "/api/admin/catalog/categories/" + idCategoria,
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM category WHERE id = ?", idCategoria)).isEqualTo(1);
                    assertThat(enteroEnBd("SELECT count(*) FROM product WHERE category_id = ?", idCategoria))
                            .isEqualTo(6);
                }),

                paso("Borrar la categoría vacía sí se permite", () -> {
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/catalog/categories/" + idCategoriaVacia,
                            tokenAdmin, null).status()).isEqualTo(204);
                    assertThat(enteroEnBd("SELECT count(*) FROM category WHERE id = ?", idCategoriaVacia)).isZero();
                }),

                paso("Borrarla otra vez responde 404 (estado imposible)", () ->
                        assertThat(llamar(HttpMethod.DELETE, "/api/admin/catalog/categories/" + idCategoriaVacia,
                                tokenAdmin, null).status()).isEqualTo(404)));
    }

    /* ── 6. Precios, márgenes y MOQ ─────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloquePreciosYMargenes() {
        return List.of(
                paso("Ve la regla global del 150 % que rige el escaparate", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/pricing/rules", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("scope").asText()).isEqualTo("GLOBAL");
                    importeExacto("margen global", r.cuerpo().get(0).get("marginValue"), "150");
                    assertThat(r.cuerpo().get(0).get("channel").asText()).isEqualTo("STOREFRONT");
                }),

                paso("Una regla SIN descripción se rechaza: nadie debe tocar precios sin explicar por qué",
                        () -> assertThat(llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                                Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 10))
                                .status()).isEqualTo(400)),

                paso("Una regla con un ámbito inventado se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                                Map.of("scope", "GALAXIA", "marginType", "PERCENTAGE", "marginValue", 10,
                                        "description", "ámbito inventado")).status()).isEqualTo(400)),

                paso("Una regla sin valor de margen se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                                Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE",
                                        "description", "sin valor")).status()).isEqualTo(400)),

                paso("Crea un margen del 300 % SOLO para este producto y el precio pasa a 52,00 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                            Map.of("scope", "PRODUCT", "scopeId", idProducto.toString(),
                                    "marginType", "PERCENTAGE", "marginValue", 300, "active", true,
                                    "position", 0, "description", "Certificación: margen del producto"));
                    assertThat(r.status()).isEqualTo(201);
                    idReglaProducto = UUID.fromString(r.cuerpo().get("id").asText());
                    vaciarCaches();
                    // 10,00 de coste × (1 + 300/100) = 40,00 de base; el IVA y el envío llevan el mismo factor
                    // 4 → 4,00 + 8,00; total 52,00.
                    // El precio de venta en dólares (retailUsd) solo se sirve al ADMIN: el escaparate
                    // enseña displayPrice y nada más. Por eso el desglose se comprueba por su ficha.
                    Respuesta detalle = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    importeExacto("precio con el margen del producto", detalle.cuerpo().get("retailUsd"), "52.00");
                    assertThat(detalle.cuerpo().get("baseFormatted").asText()).isEqualTo("$40.00");
                    Respuesta escaparate = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    assertThat(escaparate.cuerpo().get("displayFormatted").asText())
                            .as("y el cliente ve exactamente ese precio").isEqualTo("$52.00");
                }),

                paso("El margen del producto MANDA sobre el global (el ámbito más específico gana)", () -> {
                    Respuesta otro = llamar(HttpMethod.GET, "/api/admin/catalog/products?q=Gorra&lang=es",
                            tokenAdmin, null);
                    // La gorra no tiene regla propia: sigue con el global. 40 CNY / 8 = 5,00 → ×2,5 = 12,50.
                    // Su envío y su IVA en yuanes son los mismos del alta, y llevan el mismo factor 2,5
                    // que el coste: +2,50 y +5,00 → 20,00.
                    importeExacto("la gorra conserva el margen global",
                            otro.cuerpo().get("items").get(0).get("displayPrice"), "20.00");
                }),

                paso("Un rango que empieza UN CÉNTIMO por encima del coste NO se aplica", () -> {
                    // El coste de la gorra son 40 CNY / 8 = 5,0000 $ exactos. Una regla que arranca en 5,01
                    // se queda a un céntimo: no debe tocar su precio.
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                            Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 900,
                                    "minCostUsd", 5.01, "active", true, "position", 0,
                                    "description", "Certificación: fuera de rango por un céntimo"));
                    assertThat(r.status()).isEqualTo(201);
                    UUID fueraDeRango = UUID.fromString(r.cuerpo().get("id").asText());
                    vaciarCaches();
                    Respuesta gorra = llamar(HttpMethod.GET, "/api/admin/catalog/products?q=Gorra&lang=es",
                            tokenAdmin, null);
                    importeExacto("un céntimo fuera del rango deja el precio intacto",
                            gorra.cuerpo().get("items").get(0).get("displayPrice"), "20.00");
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/pricing/rules/" + fueraDeRango, tokenAdmin,
                            null).status()).isEqualTo(204);
                    vaciarCaches();
                }),

                paso("Un rango que empieza EXACTAMENTE en el coste sí se aplica y baja el precio a 8,00 $",
                        () -> {
                            // El otro lado del mismo borde: [5,00 , 5,00] contiene el coste exacto de la gorra
                            // y es el rango más ESTRECHO, así que gana a la regla global sin acotar.
                            // Margen 0 → base 5,00 + 1,00 de IVA + 2,00 de envío = 8,00.
                            Respuesta r = llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                                    Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 0,
                                            "minCostUsd", 5.00, "maxCostUsd", 5.00, "active", true,
                                            "position", 0, "description", "Certificación: justo en el borde"));
                            assertThat(r.status()).isEqualTo(201);
                            UUID enElBorde = UUID.fromString(r.cuerpo().get("id").asText());
                            vaciarCaches();
                            Respuesta gorra = llamar(HttpMethod.GET, "/api/admin/catalog/products?q=Gorra&lang=es",
                                    tokenAdmin, null);
                            importeExacto("el borde del rango sí entra",
                                    gorra.cuerpo().get("items").get(0).get("displayPrice"), "8.00");
                            // Y el producto principal, cuyo coste es 10,00, queda fuera de ese rango: sigue
                            // mandando su propia regla de producto.
                            Respuesta detalle = llamar(HttpMethod.GET,
                                    "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                            importeExacto("el producto principal queda fuera del rango",
                                    detalle.cuerpo().get("retailUsd"), "52.00");
                            assertThat(llamar(HttpMethod.DELETE, "/api/admin/pricing/rules/" + enElBorde,
                                    tokenAdmin, null).status()).isEqualTo(204);
                            vaciarCaches();
                        }),

                paso("Apaga el margen del producto y el precio vuelve a 32,50 $", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/pricing/rules/" + idReglaProducto + "/toggle",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("active").asBoolean()).isFalse();
                    vaciarCaches();
                    Respuesta detalle = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    importeExacto("sin la regla del producto manda la global", detalle.cuerpo().get("retailUsd"),
                            "32.50");
                }),

                paso("Un lote de reglas SIN identificadores se rechaza", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/rules/bulk-toggle", tokenAdmin,
                                Map.of("ids", List.of(), "active", true)).status()).isEqualTo(400)),

                paso("Borra la regla del producto y borrarla otra vez responde 404", () -> {
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/pricing/rules/" + idReglaProducto, tokenAdmin,
                            null).status()).isEqualTo(204);
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/pricing/rules/" + idReglaProducto, tokenAdmin,
                            null).status()).isEqualTo(404);
                    vaciarCaches();
                }),

                paso("El ajuste por MOQ viene activo y a la mitad", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/pricing/moq-rule", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("enabled").asBoolean()).isTrue();
                    importeExacto("factor del ajuste MOQ", r.cuerpo().get("factorPercent"), "50");
                }),

                paso("Con MOQ 1 el margen va entero: 32,50 $. Al exigir 2 unidades baja a 22,75 $", () -> {
                    // El pedido mínimo se paga con margen: si el cliente tiene que llevarse 2 o más, el
                    // margen que le corresponda se reduce a la mitad. 150 % → 75 %:
                    //   10,00 de coste × (1 + 0,75) = 17,50 de base; el IVA y el envío llevan el mismo
                    //   factor 1,75 → 1,75 + 3,50; total 22,75.
                    Respuesta antes = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    importeExacto("con MOQ 1 el margen es entero", antes.cuerpo().get("retailUsd"), "32.50");

                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                            tokenAdmin, Map.of("moq", 2)).status()).isEqualTo(200);
                    vaciarCaches();
                    Respuesta conMoq = llamar(HttpMethod.GET,
                            "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin, null);
                    importeExacto("con MOQ 2 el margen se parte por la mitad",
                            conMoq.cuerpo().get("retailUsd"), "22.75");
                    assertThat(conMoq.cuerpo().get("baseFormatted").asText()).isEqualTo("$17.50");
                }),

                paso("El BORDE del ajuste es MOQ 2: con MOQ 1 no aplica, con 2 sí", () -> {
                    // La condición es moq > 1. Se comprueba el borde exacto por los dos lados.
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                            tokenAdmin, Map.of("moq", 1)).status()).isEqualTo(200);
                    vaciarCaches();
                    importeExacto("MOQ 1 queda fuera del ajuste",
                            llamar(HttpMethod.GET, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                                    tokenAdmin, null).cuerpo().get("retailUsd"), "32.50");
                }),

                paso("Apagar el ajuste devuelve el margen entero aunque el MOQ sea mayor que uno", () -> {
                    llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin,
                            Map.of("moq", 5));
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                            Map.of("enabled", false, "factorPercent", 50)).status()).isEqualTo(200);
                    vaciarCaches();
                    importeExacto("sin ajuste, el MOQ deja de importar",
                            llamar(HttpMethod.GET, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                                    tokenAdmin, null).cuerpo().get("retailUsd"), "32.50");
                }),

                paso("Un factor del 0 % deja el producto con MOQ al precio de COSTE más IVA y envío", () -> {
                    // Valor límite por abajo: el margen se anula del todo → 10,00 + 1,00 + 2,00 = 13,00.
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                            Map.of("enabled", true, "factorPercent", 0)).status()).isEqualTo(200);
                    vaciarCaches();
                    importeExacto("margen anulado por el ajuste",
                            llamar(HttpMethod.GET, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                                    tokenAdmin, null).cuerpo().get("retailUsd"), "13.00");
                }),

                paso("Un factor por encima de 100 se rechaza", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                                Map.of("enabled", true, "factorPercent", 101)).status()).isEqualTo(400)),

                paso("Un factor negativo se rechaza", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                                Map.of("enabled", true, "factorPercent", -1)).status()).isEqualTo(400)),

                paso("Deja el ajuste y el MOQ como estaban para el resto del recorrido", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                            Map.of("enabled", true, "factorPercent", 50)).status()).isEqualTo(200);
                    llamar(HttpMethod.PUT, "/api/admin/catalog/products/" + idProducto + "?lang=es", tokenAdmin,
                            Map.of("moq", 1));
                    vaciarCaches();
                    importeExacto("de vuelta al precio del recorrido",
                            llamar(HttpMethod.GET, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                                    tokenAdmin, null).cuerpo().get("retailUsd"), "32.50");
                }));
    }

    /* ── 6-bis. Promociones y cupones ───────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloquePromociones() {
        return List.of(
                paso("Crea una rebaja automática del 5 % sobre el producto: 32,50 → 30,88 $", () -> {
                    // Sin código: se aplica sola en el escaparate. 32,50 × 0,95 = 30,875 → 30,88 al céntimo.
                    Map<String, Object> rebaja = promocion("Rebaja de certificación", null,
                            new BigDecimal("5"), null);
                    rebaja.put("scope", "PRODUCT");
                    rebaja.put("productIds", List.of(idProducto.toString()));
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, rebaja);
                    assertThat(r.status()).isEqualTo(200);
                    idPromocion = UUID.fromString(r.cuerpo().get("id").asText());
                    assertThat(r.cuerpo().get("live").asBoolean()).isTrue();
                    vaciarCaches();

                    Respuesta ficha = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    importeExacto("precio ya rebajado", ficha.cuerpo().get("displayPrice"), "30.88");
                    assertThat(ficha.cuerpo().get("originalFormatted").asText())
                            .as("y se enseña tachado el de antes").isEqualTo("$32.50");
                    assertThat(ficha.cuerpo().get("discountPercent").asInt()).isEqualTo(5);
                }),

                paso("El SUELO del precio protege el margen: un 25 % no puede bajar del precio base", () -> {
                    // 32,50 × 0,75 = 24,375, por debajo de los 25,00 de base con margen. El suelo lo corta
                    // ahí: se vende a 25,00 y el porcentaje anunciado se recalcula → (32,50−25)/32,50 = 23,07 → 23.
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/promotions/" + idPromocion, tokenAdmin,
                            promocionProducto("Rebaja de certificación", new BigDecimal("25"))).status())
                            .isEqualTo(200);
                    vaciarCaches();
                    Respuesta ficha = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    importeExacto("el suelo corta la rebaja", ficha.cuerpo().get("displayPrice"), "25.00");
                    assertThat(ficha.cuerpo().get("discountPercent").asInt())
                            .as("el porcentaje anunciado es el REAL tras el suelo").isEqualTo(23);
                }),

                paso("La rebaja se anuncia en el escaparate de promociones vivas", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/promotions/live?lang=es", null, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("id").asText()).isEqualTo(idPromocion.toString());
                    assertThat(r.cuerpo().get(0).get("products")).isNotEmpty();
                }),

                paso("Un cupón NUNCA se anuncia en el escaparate", () -> {
                    // Es la diferencia con la rebaja: el cupón solo lo conoce quien recibe el código.
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            promocion("Cupón de certificación", "ADMINCERT", new BigDecimal("20"), null))
                            .status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/promotions/live?lang=es", null, null);
                    List<String> anunciadas = new ArrayList<>();
                    r.cuerpo().forEach(n -> anunciadas.add(n.get("name").asText()));
                    assertThat(anunciadas).doesNotContain("Cupón de certificación");
                }),

                paso("Repetir el código de un cupón se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Otro", "ADMINCERT", new BigDecimal("20"), null)).status())
                                .isEqualTo(422)),

                paso("Pedir porcentaje E importe a la vez se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Ambos", "CERTAMBOS", new BigDecimal("10"), 500)).status())
                                .isEqualTo(422)),

                paso("No indicar ni porcentaje ni importe también se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Ninguno", "CERTNADA", null, null)).status()).isEqualTo(422)),

                paso("Un 100 % se rechaza: el tope del porcentaje es 99", () ->
                        // Valor límite por arriba: 99 vale, 100 no.
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Todo gratis", "CERT100", new BigDecimal("100"), null)).status())
                                .isEqualTo(422)),

                paso("Un 99 % sí se acepta (el borde por arriba)", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            promocion("Casi gratis", "CERT99", new BigDecimal("99"), null));
                    assertThat(r.status()).isEqualTo(200);
                    llamar(HttpMethod.DELETE, "/api/admin/promotions/" + r.cuerpo().get("id").asText(),
                            tokenAdmin, null);
                }),

                paso("Un 0 % se rechaza: el mínimo del porcentaje es 1", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Cero", "CERT0", BigDecimal.ZERO, null)).status()).isEqualTo(422)),

                paso("Un importe fijo de cero se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                                promocion("Cero fijo", "CERT0F", null, 0)).status()).isEqualTo(422)),

                paso("Una ventana que acaba ANTES de empezar se rechaza", () -> {
                    Map<String, Object> alReves = promocion("Al revés", "CERTREVES", new BigDecimal("10"), null);
                    alReves.put("startsAt", Instant.now().plus(5, ChronoUnit.DAYS).toString());
                    alReves.put("endsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, alReves).status())
                            .isEqualTo(422);
                }),

                paso("Un alcance por CATEGORÍA sin categorías se rechaza", () -> {
                    Map<String, Object> sinCategorias = promocion("Sin categorías", "CERTSINCAT",
                            new BigDecimal("10"), null);
                    sinCategorias.put("scope", "CATEGORY");
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, sinCategorias)
                            .status()).isEqualTo(422);
                }),

                paso("Una promoción SIN nombre se rechaza por validación", () -> {
                    Map<String, Object> sinNombre = promocion("", "CERTSINNOMBRE", new BigDecimal("10"), null);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, sinNombre).status())
                            .isEqualTo(400);
                }),

                paso("Apaga la rebaja con el interruptor y el precio vuelve a 32,50 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/promotions/" + idPromocion + "/toggle",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("active").asBoolean()).isFalse();
                    vaciarCaches();
                    Respuesta ficha = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    importeExacto("sin rebaja, el precio de siempre", ficha.cuerpo().get("displayPrice"),
                            "32.50");
                    assertThat(ficha.cuerpo().get("discountPercent").isNull()).isTrue();
                }),

                paso("Editar una promoción que no existe responde 404", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/promotions/" + UUID.randomUUID(),
                                tokenAdmin, promocionProducto("Fantasma", new BigDecimal("10"))).status())
                                .isEqualTo(404)),

                paso("Borra la promoción; repetir el borrado es idempotente y no la resucita", () -> {
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/promotions/" + idPromocion, tokenAdmin,
                            null).status()).isEqualTo(204);
                    assertThat(enteroEnBd("SELECT count(*) FROM promotion WHERE id = ?", idPromocion)).isZero();
                    // El borrado no comprueba existencia previa: repetirlo responde 204 igualmente. No es
                    // un fallo (el efecto buscado ya se cumple), pero queda fijado por escrito.
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/promotions/" + idPromocion, tokenAdmin,
                            null).status()).isEqualTo(204);
                    assertThat(enteroEnBd("SELECT count(*) FROM promotion WHERE id = ?", idPromocion)).isZero();
                    vaciarCaches();
                }),

                paso("Limpia el cupón de administración para no alterar el resto del recorrido", () -> {
                    List<String> ids = jdbcTemplate.queryForList(
                            "SELECT id::text FROM promotion WHERE code IS NOT NULL", String.class);
                    for (String id : ids) {
                        llamar(HttpMethod.DELETE, "/api/admin/promotions/" + id, tokenAdmin, null);
                    }
                    assertThat(enteroEnBd("SELECT count(*) FROM promotion")).isZero();
                    vaciarCaches();
                }));
    }

    /* ── 7. El pedido del cliente: estados, envío, entrega y reembolso ──────────────────────────── */

    private List<DynamicTest> bloquePedidoDelCliente() {
        return List.of(
                paso("Un cliente compra dos unidades y paga 91,42 $ con su monedero", () -> {
                    Respuesta direccion = llamar(HttpMethod.POST, "/api/me/addresses", tokenCliente,
                            Map.of("fullName", "Cliente Cert", "line1", "Calle Mayor 1", "city", "Madrid",
                                    "postalCode", "28001", "country", "ES"));
                    assertThat(direccion.status()).isEqualTo(201);

                    Map<String, Object> compra = new LinkedHashMap<>();
                    compra.put("shippingAddressId", direccion.cuerpo().get("id").asText());
                    compra.put("items", List.of(linea(idProducto, idVariante, 2)));
                    compra.put("paymentMethod", "WALLET");
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente, compra);
                    assertThat(r.status()).isEqualTo(201);
                    idPedido = UUID.fromString(r.cuerpo().get("id").asText());
                    importeExacto("total pagado por el cliente", r.cuerpo().get("total"), "91.42");
                    assertThat(saldoCliente()).isEqualTo(SALDO_INICIAL - TOTAL_PEDIDO);
                }),

                paso("El administrador ve el pedido en su bandeja con el importe exacto", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/orders?page=0&size=20", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                    JsonNode fila = r.cuerpo().get("items").get(0);
                    assertThat(fila.get("id").asText()).isEqualTo(idPedido.toString());
                    assertThat(fila.get("status").asText()).isEqualTo("PAID");
                    assertThat(fila.get("totalCents").asLong()).isEqualTo(TOTAL_PEDIDO);
                    assertThat(fila.get("subtotalCents").asLong()).isEqualTo(UNIDAD_CENTIMOS * 2L);
                    assertThat(fila.get("shippingCents").asLong())
                            .isEqualTo(ENVIO_ES_CENTIMOS + DESPACHO_ES_CENTIMOS);
                    assertThat(fila.get("source").asText()).isEqualTo("PLATFORM");
                    assertThat(fila.get("totalFormatted").asText()).isEqualTo("$91.42");
                }),

                paso("Filtrar la bandeja por un estado que nadie tiene la deja vacía", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/orders?status=DELIVERED", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isZero();
                }),

                paso("El detalle del pedido trae la línea comprada con sus importes al céntimo", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/orders/" + idPedido + "?lang=es",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("PAID");
                    assertThat(r.cuerpo().get("items")).hasSize(1);
                    JsonNode linea = r.cuerpo().get("items").get(0);
                    assertThat(linea.get("qty").asInt()).isEqualTo(2);
                    assertThat(linea.get("unitPriceCents").asInt()).isEqualTo(UNIDAD_CENTIMOS);
                    assertThat(linea.get("lineTotalCents").asInt()).isEqualTo(UNIDAD_CENTIMOS * 2);
                }),

                paso("Un pedido inexistente responde 404", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/admin/orders/" + UUID.randomUUID(), tokenAdmin,
                                null).status()).isEqualTo(404)),

                paso("No se puede marcar EN CAMINO un pedido que aún no salió al proveedor", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/ship",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(textoEnBd("SELECT status FROM customer_order WHERE id = ?", idPedido))
                            .as("el estado no se mueve").isEqualTo("PAID");
                }),

                paso("No se puede ENTREGAR un pedido que no está en camino", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/deliver",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(textoEnBd("SELECT status FROM customer_order WHERE id = ?", idPedido))
                            .isEqualTo("PAID");
                    assertThat(enteroEnBd("SELECT count(*) FROM operator_order_action WHERE order_id = ?",
                            idPedido)).as("una entrega rechazada no acredita comisión").isZero();
                }),

                paso("Lo envía al proveedor: pasa a REMITIDO y queda sellada la fecha", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/forward",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("FORWARDED");
                    assertThat(r.cuerpo().get("forwardedAt").isNull()).isFalse();
                }),

                paso("DEFECTO · reenviar al proveedor un pedido ya remitido no avisa de nada", () -> {
                    // Comportamiento REAL: forwardOrder solo actúa desde PENDING/AWAITING_PAYMENT/PAID y en
                    // cualquier otro estado devuelve 200 con el pedido intacto, en silencio. Enviar y
                    // entregar sí rechazan con 422, así que la incoherencia está en este único punto: el
                    // panel no distingue "hecho" de "no se ha hecho nada".
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/forward",
                            tokenAdmin, null);
                    assertThat(r.status()).as("hoy responde 200 en vez de rechazar el estado imposible")
                            .isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("FORWARDED");
                }),

                paso("Lo marca EN CAMINO", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/ship",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("SHIPPED");
                    assertThat(r.cuerpo().get("shippedAt").isNull()).isFalse();
                }),

                paso("El cliente ve el cambio en el seguimiento de SU pedido", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/orders/" + idPedido + "/tracking",
                            tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("SHIPPED");
                }),

                paso("Volver a marcarlo en camino se rechaza (estado imposible)", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/ship", tokenAdmin,
                                null).status()).isEqualTo(422)));
    }

    /* ── 8. Operadores y sus comisiones ─────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueOperadores() {
        return List.of(
                paso("El OPERADOR entrega el pedido: es su trabajo y llega a esa ruta", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/deliver",
                            tokenOperador, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("DELIVERED");
                    assertThat(r.cuerpo().get("deliveredAt").isNull()).isFalse();
                }),

                paso("La entrega le acredita 14,16 ¥ de comisión: el 10 % de la base sin IVA", () -> {
                    // 80,00 ¥ × 2 = 160,00 ¥ brutos → /1,13 = 141,592920 de base → ×10 % = 14,159292
                    // → 1.416 fen al céntimo más cercano. Se comprueba contra el apunte guardado.
                    Map<String, Object> apunte = jdbcTemplate.queryForMap(
                            "SELECT commission_cny_cents, item_count, order_source, commission_pct, action"
                                    + " FROM operator_order_action WHERE order_id = ?", idPedido);
                    assertThat(apunte.get("action")).isEqualTo("DELIVERED");
                    assertThat(((Number) apunte.get("commission_cny_cents")).longValue())
                            .isEqualTo(COMISION_CNY_CENTIMOS);
                    assertThat(((Number) apunte.get("item_count")).intValue()).isEqualTo(2);
                    assertThat(apunte.get("order_source")).isEqualTo("PLATFORM");
                    assertThat(new BigDecimal(apunte.get("commission_pct").toString()))
                            .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("10"));
                }),

                paso("El informe de operadores del administrador suma esa comisión y ni una más", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/operators/report", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    JsonNode fila = r.cuerpo().get(0);
                    assertThat(fila.get("operatorSubject").asText()).isEqualTo(idOperador.toString());
                    assertThat(fila.get("operations").asInt()).isEqualTo(1);
                    assertThat(fila.get("totalCommissionCnyCents").asLong()).isEqualTo(COMISION_CNY_CENTIMOS);
                    assertThat(fila.get("currency").asText()).isEqualTo("CNY");
                }),

                paso("El propio operador consulta SUS ganancias por la ruta que sí tiene abierta", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/operator/earnings", tokenOperador, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalCommissionCnyCents").asLong())
                            .isEqualTo(COMISION_CNY_CENTIMOS);
                    assertThat(r.cuerpo().get("operations").asInt()).isEqualTo(1);
                }),

                paso("Su histórico de operaciones muestra la entrega que hizo", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/operator/history?page=0&size=20",
                            tokenOperador, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("total").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("items").get(0).get("orderId").asText())
                            .isEqualTo(idPedido.toString());
                    assertThat(r.cuerpo().get("items").get(0).get("commissionCnyCents").asLong())
                            .isEqualTo(COMISION_CNY_CENTIMOS);
                }),

                paso("El operador NO puede ver el informe global de todos los operadores", () ->
                        // La ruta en plural es del administrador; la singular es la del propio operador.
                        // Una letra separa los dos niveles de acceso y así queda fijado por escrito.
                        assertThat(llamar(HttpMethod.GET, "/api/admin/operators/report", tokenOperador, null)
                                .status()).isEqualTo(403)),

                paso("Un usuario NORMAL no llega a ninguna de las dos rutas", () -> {
                    assertThat(llamar(HttpMethod.GET, "/api/admin/operators/report", tokenUsuarioNormal, null)
                            .status()).isEqualTo(403);
                    assertThat(llamar(HttpMethod.GET, "/api/admin/operator/earnings", tokenUsuarioNormal, null)
                            .status()).isEqualTo(403);
                }),

                paso("Entregar dos veces no acredita la comisión otra vez", () -> {
                    // La segunda entrega se rechaza por estado; el apunte sigue siendo uno solo.
                    assertThat(llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/deliver", tokenOperador,
                            null).status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM operator_order_action WHERE order_id = ?", idPedido))
                            .isEqualTo(1);
                }),

                paso("Reembolsa el pedido entregado y el cliente recupera 91,42 $ EXACTOS", () -> {
                    long antes = saldoCliente();
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/refund",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("REFUNDED");
                    assertThat(saldoCliente()).isEqualTo(antes + TOTAL_PEDIDO);
                    assertThat(saldoCliente()).as("vuelve al saldo de partida").isEqualTo(SALDO_INICIAL);
                }),

                paso("Reembolsar otra vez es idempotente y NO devuelve el dinero dos veces", () -> {
                    long antes = saldoCliente();
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/refund",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("REFUNDED");
                    assertThat(saldoCliente()).as("el dinero no se puede devolver dos veces").isEqualTo(antes);
                }),

                paso("Cancelar un pedido YA reembolsado no vuelve a mover dinero", () -> {
                    long antes = saldoCliente();
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/cancel",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("CANCELLED");
                    assertThat(saldoCliente()).isEqualTo(antes);
                }),

                paso("Reembolsar un pedido cancelado se rechaza y no mueve un céntimo", () -> {
                    long antes = saldoCliente();
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/refund",
                            tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(saldoCliente()).isEqualTo(antes);
                    assertThat(textoEnBd("SELECT status FROM customer_order WHERE id = ?", idPedido))
                            .isEqualTo("CANCELLED");
                }),

                paso("Cancelar un pedido ya cancelado es idempotente", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/orders/" + idPedido + "/cancel", tokenAdmin,
                                null).cuerpo().get("status").asText()).isEqualTo("CANCELLED")),

                paso("Un lote de reembolsos sobre identificadores inexistentes no tumba la petición", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/orders/bulk-refund", tokenAdmin,
                            List.of(UUID.randomUUID().toString()));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("succeeded").asInt()).isZero();
                    assertThat(r.cuerpo().get("failed").asInt()).isEqualTo(1);
                }));
    }

    /* ── 9. Gestión de usuarios ─────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueUsuarios() {
        return List.of(
                paso("Lista los usuarios de la plataforma", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/users?page=0&size=25", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    // Administrador, cliente, operador y el usuario normal de los cruces.
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(4);
                }),

                paso("Filtra por rol y encuentra exactamente al operador", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/users?role=OPERATOR", tokenAdmin, null);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                    assertThat(r.cuerpo().get("items").get(0).get("id").asText()).isEqualTo(idOperador.toString());
                }),

                paso("Crea un usuario desde el panel", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/users", tokenAdmin,
                            Map.of("email", "cert-gestionado@example.com", "password", "GestionAdmin123!",
                                    "role", "USER", "displayName", "Usuario Gestionado"));
                    assertThat(r.status()).isEqualTo(201);
                    idUsuarioGestionado = UUID.fromString(r.cuerpo().get("id").asText());
                }),

                paso("Crear otro usuario con el MISMO correo se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/users", tokenAdmin,
                                Map.of("email", "cert-gestionado@example.com", "password", "GestionAdmin123!",
                                        "role", "USER")).status()).isEqualTo(409)),

                paso("Un rol inventado se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/users", tokenAdmin,
                                Map.of("email", "cert-rol-malo@example.com", "password", "GestionAdmin123!",
                                        "role", "SUPERJEFE")).status()).isEqualTo(400)),

                paso("Edita su ficha: nombre, empresa y país", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/users/" + idUsuarioGestionado, tokenAdmin,
                            Map.of("displayName", "Gestionado Editado", "companyName", "NX036 SL",
                                    "country", "ES"));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("displayName").asText()).isEqualTo("Gestionado Editado");
                    assertThat(r.cuerpo().get("companyName").asText()).isEqualTo("NX036 SL");
                }),

                paso("Le sube el rol a OPERADOR y se lo vuelve a bajar", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/users/" + idUsuarioGestionado + "/role",
                            tokenAdmin, Map.of("role", "OPERATOR")).cuerpo().get("role").asText())
                            .isEqualTo("OPERATOR");
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/users/" + idUsuarioGestionado + "/role",
                            tokenAdmin, Map.of("role", "USER")).cuerpo().get("role").asText()).isEqualTo("USER");
                }),

                paso("Le bloquea la cuenta durante una hora y se la desbloquea", () -> {
                    Respuesta bloqueo = llamar(HttpMethod.POST, "/api/admin/users/" + idUsuarioGestionado
                            + "/lock?minutes=60", tokenAdmin, null);
                    assertThat(bloqueo.status()).isEqualTo(200);
                    assertThat(bloqueo.cuerpo().get("lockedUntil").isNull()).isFalse();
                    Respuesta desbloqueo = llamar(HttpMethod.POST, "/api/admin/users/" + idUsuarioGestionado
                            + "/unlock", tokenAdmin, null);
                    assertThat(desbloqueo.cuerpo().get("lockedUntil").isNull()).isTrue();
                }),

                paso("Le activa la cuenta manualmente", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/admin/users/" + idUsuarioGestionado + "/activate",
                                tokenAdmin, null).cuerpo().get("active").asBoolean()).isTrue()),

                paso("Le reinicia la contraseña y le llega el correo", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/admin/users/" + idUsuarioGestionado
                            + "/reset-password", tokenAdmin, null).status()).isEqualTo(204);
                    assertThat(enteroEnBd("SELECT count(*) FROM outbound_email WHERE to_address = ?",
                            "cert-gestionado@example.com")).isPositive();
                }),

                paso("No puede borrar a OTRO administrador", () -> {
                    UUID otroAdmin = UUID.randomUUID();
                    crearUsuario(otroAdmin, "cert-admin-2@example.com", "ADMIN");
                    Respuesta r = llamar(HttpMethod.DELETE, "/api/admin/users/" + otroAdmin, tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(enteroEnBd("SELECT count(*) FROM users WHERE id = ?", otroAdmin))
                            .as("la cuenta de administrador sigue ahí").isEqualTo(1);
                    jdbcTemplate.update("DELETE FROM users WHERE id = ?", otroAdmin);
                }),

                paso("El borrado del panel es LÓGICO: anonimiza y desactiva, pero conserva la fila", () -> {
                    // La fila NO puede desaparecer: pedidos, facturas y el libro del monedero la
                    // referencian y esos datos hay que conservarlos. Lo que se borra es el dato personal.
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/users/" + idUsuarioGestionado, tokenAdmin,
                            null).status()).isEqualTo(204);
                    Map<String, Object> fila = jdbcTemplate.queryForMap(
                            "SELECT email, active, deleted_at, display_name FROM users WHERE id = ?",
                            idUsuarioGestionado);
                    assertThat(fila).as("la fila sigue existiendo").isNotNull();
                    assertThat((Boolean) fila.get("active")).as("queda desactivada").isFalse();
                    assertThat(fila.get("deleted_at")).as("con su fecha de baja").isNotNull();
                    assertThat((String) fila.get("email"))
                            .as("y el correo real ya no está").isNotEqualTo("cert-gestionado@example.com");
                }),

                paso("Sus direcciones, que son dato personal sin obligación de conservar, sí se borran",
                        () -> assertThat(enteroEnBd("SELECT count(*) FROM user_address WHERE user_id = ?",
                                idUsuarioGestionado)).isZero()),

                paso("Y desaparece del listado del panel", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/users?page=0&size=50", tokenAdmin, null);
                    List<String> visibles = new ArrayList<>();
                    r.cuerpo().get("items").forEach(n -> visibles.add(n.get("id").asText()));
                    assertThat(visibles).doesNotContain(idUsuarioGestionado.toString());
                }),

                paso("Volver a borrarlo es idempotente y no vuelve a tocar nada", () -> {
                    String correoAnonimo = textoEnBd("SELECT email FROM users WHERE id = ?", idUsuarioGestionado);
                    assertThat(llamar(HttpMethod.DELETE, "/api/admin/users/" + idUsuarioGestionado, tokenAdmin,
                            null).status()).isEqualTo(204);
                    assertThat(enteroEnBd("SELECT count(*) FROM users WHERE id = ?", idUsuarioGestionado))
                            .isEqualTo(1);
                    assertThat(textoEnBd("SELECT email FROM users WHERE id = ?", idUsuarioGestionado))
                            .isNotEqualTo(correoAnonimo);
                }),

                paso("Borrar a alguien que nunca existió responde 404", () ->
                        assertThat(llamar(HttpMethod.DELETE, "/api/admin/users/" + UUID.randomUUID(), tokenAdmin,
                                null).status()).isEqualTo(404)),

                paso("Invita a un usuario nuevo, y repetir la invitación se rechaza", () -> {
                    Respuesta invitacion = llamar(HttpMethod.POST, "/api/admin/users/invite", tokenAdmin,
                            Map.of("email", "cert-invitado@example.com", "role", "USER"));
                    assertThat(invitacion.status()).isEqualTo(201);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/users/invite", tokenAdmin,
                            Map.of("email", "cert-invitado@example.com", "role", "USER")).status())
                            .isEqualTo(409);
                }));
    }

    /* ── 10. Monedas y tasas ────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueMonedas() {
        return List.of(
                paso("Ve todas las monedas configuradas, activas e inactivas", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/currency/all", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(3);
                    assertThat(buscar(r.cuerpo(), "code", "CNY").get("active").asBoolean()).isTrue();
                }),

                paso("Cambia la tasa del euro y el cambio se refleja de inmediato", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/currency/EUR", tokenAdmin,
                            Map.of("rateVsUsd", 0.5, "active", true));
                    assertThat(r.status()).isEqualTo(200);
                    importeExacto("tasa guardada", r.cuerpo().get("rateVsUsd"), "0.5");
                    vaciarCaches();
                    // Con 0,5 € por dólar, el producto de 32,50 $ se enseña a 16,25 €, exacto.
                    Respuesta ficha = llamarConMoneda("/api/catalog/products/" + slugProducto + "?lang=es", "EUR");
                    importeExacto("precio en euros", ficha.cuerpo().get("displayPrice"), "16.25");
                    assertThat(ficha.cuerpo().get("displayCurrency").asText()).isEqualTo("EUR");
                }),

                paso("Una tasa negativa se rechaza", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/EUR", tokenAdmin,
                                Map.of("rateVsUsd", -1)).status()).isEqualTo(400)),

                paso("Una tasa de CERO también se rechaza: no es un cambio, es una división por cero", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/EUR", tokenAdmin,
                                Map.of("rateVsUsd", 0)).status()).isEqualTo(400)),

                paso("Una moneda que no existe responde 404", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/XXX", tokenAdmin,
                                Map.of("rateVsUsd", 1)).status()).isEqualTo(404)),

                paso("Desactiva el euro y deja de publicarse en la lista de monedas activas", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/EUR/active?active=false", tokenAdmin,
                            null).cuerpo().get("active").asBoolean()).isFalse();
                    Respuesta activas = llamar(HttpMethod.GET, "/api/currency/rates", null, null);
                    List<String> codigos = new ArrayList<>();
                    activas.cuerpo().forEach(n -> codigos.add(n.get("code").asText()));
                    assertThat(codigos).containsExactlyInAnyOrder("USD", "CNY");
                }),

                paso("Sin el parámetro obligatorio, el interruptor de la moneda se rechaza", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/EUR/active", tokenAdmin, null)
                                .status()).isEqualTo(400)),

                paso("Activa varias monedas en un solo movimiento", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/currency/bulk-active", tokenAdmin,
                            Map.of("codes", List.of("EUR"), "active", true));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("changed").asInt()).isEqualTo(1);
                }),

                paso("Un lote de monedas VACÍO no cambia nada", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/currency/bulk-active", tokenAdmin,
                            Map.of("codes", List.of(), "active", true));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("changed").asInt()).isZero();
                }),

                paso("Devuelve el euro a su tasa original para no arrastrar el cambio", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/EUR", tokenAdmin,
                            Map.of("rateVsUsd", 0.9, "active", true)).status()).isEqualTo(200);
                    vaciarCaches();
                }));
    }

    /* ── 11. Reglas aduaneras por país ──────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueAduanas() {
        return List.of(
                paso("Lista las reglas aduaneras configuradas", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/customs-rules", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    JsonNode espana = buscar(r.cuerpo(), "countryCode", "ES");
                    assertThat(espana.get("taxMode").asText()).isEqualTo("DDP");
                    assertThat(espana.get("handlingFeeCents").asInt()).isEqualTo(DESPACHO_ES_CENTIMOS);
                }),

                paso("Actualiza la regla de España añadiendo un 10 % sobre el impuesto adelantado", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDP", 0, "SURCHARGE", DESPACHO_ES_CENTIMOS, 1000, 0, 0));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("handlingPercentBps").asInt()).isEqualTo(1000);

                    // El impuesto sobre (6.500 + 849) es 1.543; el recargo pasa a ser
                    // 250 + 10 % de 1.543 = 250 + 154,3 → 154 al céntimo más cercano = 404.
                    Respuesta cotizacion = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(cotizacion.cuerpo().get("amountUsdCents").asInt())
                            .isEqualTo(ENVIO_ES_CENTIMOS + 250 + 154);
                }),

                paso("Un recargo NEGATIVO se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                                reglaAduanera("DDP", 0, "SURCHARGE", -1, 0, 0, 0)).status()).isEqualTo(400)),

                paso("Un modo fiscal VACÍO se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                                reglaAduanera("", 0, "SURCHARGE", 0, 0, 0, 0)).status()).isEqualTo(400)),

                paso("En modo DDU no hay recargo: el impuesto lo paga el destinatario", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDU", 0, "SURCHARGE", DESPACHO_ES_CENTIMOS, 1000, 0, 0))
                            .status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("taxMode").asText()).isEqualTo("DDU");
                    assertThat(r.cuerpo().get("amountUsdCents").asInt())
                            .as("sin recargo de despacho, solo el porte").isEqualTo(ENVIO_ES_CENTIMOS);
                }),

                paso("Un umbral JUSTO por encima del pedido no lo supera", () -> {
                    // Borde exacto: el valor de los bienes son 65,00 $ y el umbral 65,01 → no se supera.
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDP", 65.01, "SURCHARGE", 0, 0, 5000, 0)).status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("customsThresholdExceeded").asBoolean()).isFalse();
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isEqualTo(ENVIO_ES_CENTIMOS);
                }),

                paso("Un umbral EXACTAMENTE igual al pedido tampoco lo supera (la comparación es estricta)",
                        () -> {
                            assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                                    reglaAduanera("DDP", 65.00, "SURCHARGE", 0, 0, 5000, 0)).status())
                                    .isEqualTo(200);
                            Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                                    Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                            assertThat(r.cuerpo().get("customsThresholdExceeded").asBoolean()).isFalse();
                        }),

                paso("Un céntimo por debajo del pedido SÍ lo supera y aparece el recargo formal", () -> {
                    // 64,99 < 65,00 → superado. Recargo = 50,00 fijos + 5 % del valor (3,25) = 53,25.
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDP", 64.99, "SURCHARGE", 0, 0, 5000, 500)).status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("customsThresholdExceeded").asBoolean()).isTrue();
                    assertThat(r.cuerpo().get("amountUsdCents").asInt())
                            .isEqualTo(ENVIO_ES_CENTIMOS + 5000 + 325);
                }),

                paso("Con la política de BLOQUEO, el destino rechaza la compra ANTES de cobrar", () -> {
                    assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDP", 64.99, "BLOCK", 0, 0, 0, 0)).status()).isEqualTo(200);
                    Respuesta cotizacion = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(cotizacion.cuerpo().get("customsBlocked").asBoolean()).isTrue();

                    long saldoAntes = saldoCliente();
                    Respuesta direccion = llamar(HttpMethod.GET, "/api/me/addresses", tokenCliente, null);
                    Map<String, Object> compra = new LinkedHashMap<>();
                    compra.put("shippingAddressId", direccion.cuerpo().get(0).get("id").asText());
                    compra.put("items", List.of(linea(idProducto, idVariante, 2)));
                    compra.put("paymentMethod", "WALLET");
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente, compra);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("CUSTOMS_THRESHOLD_EXCEEDED");
                    assertThat(saldoCliente()).as("un destino bloqueado no cobra nada").isEqualTo(saldoAntes);
                }),

                paso("Deja la regla como estaba: el recargo fijo del despacho DDP", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                            reglaAduanera("DDP", 0, "SURCHARGE", DESPACHO_ES_CENTIMOS, 0, 0, 0));
                    assertThat(r.status()).isEqualTo(200);
                    Respuesta cotizacion = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(cotizacion.cuerpo().get("amountUsdCents").asInt())
                            .isEqualTo(ENVIO_ES_CENTIMOS + DESPACHO_ES_CENTIMOS);
                }),

                paso("Un país SIN regla aduanera no encarece el envío", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "US", "items", List.of(linea(idProducto, idVariante, 2))));
                    // Estados Unidos no tiene regla: 7,99 + 4,50 × 1 kg = 12,49, sin recargo alguno.
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isEqualTo(1249);
                    assertThat(r.cuerpo().get("taxMode").asText()).isEqualTo("DDP");
                }));
    }

    /* ── 12. Panel de métricas ──────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueMetricas() {
        return List.of(
                paso("El panel cuadra: seis productos, un pedido y 91,42 $ de volumen", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/dashboard/metrics", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalProducts").asLong()).isEqualTo(6);
                    assertThat(r.cuerpo().get("activeProducts").asLong()).isEqualTo(6);
                    assertThat(r.cuerpo().get("draftProducts").asLong()).isZero();
                    assertThat(r.cuerpo().get("totalOrders").asLong()).isEqualTo(1);
                    // El volumen es la suma de los totales de los pedidos: un único pedido de 80,53.
                    importeExacto("volumen bruto en dólares", r.cuerpo().get("gmvUsd"), "91.42");
                    assertThat(r.cuerpo().get("displayCurrency").asText()).isEqualTo("USD");
                    // Sin planes contratados no hay ingreso recurrente que enseñar.
                    importeExacto("ingreso recurrente mensual", r.cuerpo().get("mrrUsd"), "0.00");
                }),

                paso("El número de usuarios del panel coincide con los que hay de verdad", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/dashboard/metrics", tokenAdmin, null);
                    assertThat(r.cuerpo().get("totalUsers").asLong())
                            .isEqualTo(enteroEnBd("SELECT count(*) FROM users"));
                }),

                paso("Las series diarias reparten el pedido en su día", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/dashboard/series", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    long pedidos = 0;
                    long volumen = 0;
                    for (JsonNode valor : r.cuerpo().get("ordersByDay")) {
                        pedidos += valor.asLong();
                    }
                    for (JsonNode valor : r.cuerpo().get("gmvCentsByDay")) {
                        volumen += valor.asLong();
                    }
                    assertThat(pedidos).isEqualTo(1);
                    assertThat(volumen).as("los céntimos de la serie son los del pedido").isEqualTo(TOTAL_PEDIDO);
                }),

                paso("Los últimos pedidos muestran el pedido con su estado final", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/admin/dashboard/recent-orders", tokenAdmin, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("id").asText()).isEqualTo(idPedido.toString());
                    assertThat(r.cuerpo().get(0).get("status").asText()).isEqualTo("CANCELLED");
                    assertThat(r.cuerpo().get(0).get("totalCents").asLong()).isEqualTo(TOTAL_PEDIDO);
                }));
    }

    /* ── 13. Autorización: el usuario NORMAL no puede nada de esto ──────────────────────────────── */

    private List<DynamicTest> bloqueAutorizacionCruzada() {
        List<Operacion> sensibles = List.of(
                new Operacion("dar de alta un producto", HttpMethod.POST,
                        "/api/admin/catalog/products/create", producto("Intruso", 10.0, 1)),
                new Operacion("importar productos en lote", HttpMethod.POST,
                        "/api/admin/catalog/products/bulk", List.of()),
                new Operacion("cambiar el estado de un producto", HttpMethod.PUT,
                        "/api/admin/catalog/products/" + UUID.randomUUID() + "/status",
                        Map.of("status", "PAUSED")),
                new Operacion("borrar un producto", HttpMethod.DELETE,
                        "/api/admin/catalog/products/" + UUID.randomUUID(), null),
                new Operacion("crear una categoría", HttpMethod.POST,
                        "/api/admin/catalog/categories", Map.of("slug", "intrusa", "nameZh", "x")),
                new Operacion("borrar una categoría", HttpMethod.DELETE,
                        "/api/admin/catalog/categories/" + UUID.randomUUID(), null),
                new Operacion("crear una regla de margen", HttpMethod.POST, "/api/admin/pricing/rules",
                        Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 999,
                                "description", "intrusión")),
                new Operacion("listar las reglas de margen", HttpMethod.GET, "/api/admin/pricing/rules", null),
                new Operacion("ver la bandeja de pedidos", HttpMethod.GET, "/api/admin/orders", null),
                new Operacion("remitir un pedido al proveedor", HttpMethod.POST,
                        "/api/admin/orders/" + UUID.randomUUID() + "/forward", null),
                new Operacion("marcar un pedido en camino", HttpMethod.POST,
                        "/api/admin/orders/" + UUID.randomUUID() + "/ship", null),
                new Operacion("entregar un pedido", HttpMethod.POST,
                        "/api/admin/orders/" + UUID.randomUUID() + "/deliver", null),
                new Operacion("reembolsar un pedido", HttpMethod.POST,
                        "/api/admin/orders/" + UUID.randomUUID() + "/refund", null),
                new Operacion("listar usuarios", HttpMethod.GET, "/api/admin/users", null),
                new Operacion("crear un usuario", HttpMethod.POST, "/api/admin/users",
                        Map.of("email", "intruso@example.com", "password", "Intruso123!", "role", "ADMIN")),
                new Operacion("cambiar el rol de un usuario", HttpMethod.PUT,
                        "/api/admin/users/" + UUID.randomUUID() + "/role", Map.of("role", "ADMIN")),
                new Operacion("borrar un usuario", HttpMethod.DELETE,
                        "/api/admin/users/" + UUID.randomUUID(), null),
                new Operacion("ver el informe de operadores", HttpMethod.GET,
                        "/api/admin/operators/report", null),
                new Operacion("ver las ganancias de operador", HttpMethod.GET,
                        "/api/admin/operator/earnings", null),
                new Operacion("cambiar la tasa de una moneda", HttpMethod.PUT, "/api/admin/currency/EUR",
                        Map.of("rateVsUsd", 1)),
                new Operacion("ver todas las monedas", HttpMethod.GET, "/api/admin/currency/all", null),
                new Operacion("cambiar la regla aduanera de un país", HttpMethod.PUT,
                        "/api/admin/customs-rules/ES", reglaAduanera("DDP", 0, "SURCHARGE", 0, 0, 0, 0)),
                new Operacion("ver las reglas aduaneras", HttpMethod.GET, "/api/admin/customs-rules", null),
                new Operacion("ver el panel de métricas", HttpMethod.GET, "/api/admin/dashboard/metrics", null),
                new Operacion("ver los monederos de todos", HttpMethod.GET, "/api/admin/wallets", null));

        List<DynamicTest> pasos = new ArrayList<>();
        for (Operacion operacion : sensibles) {
            pasos.add(paso("Un usuario NORMAL no puede " + operacion.descripcion() + ": 403", () -> {
                Respuesta conUsuario = llamar(operacion.metodo(), operacion.uri(), tokenUsuarioNormal,
                        operacion.cuerpo());
                assertThat(conUsuario.status())
                        .as("%s con un token de usuario normal", operacion.descripcion()).isEqualTo(403);
                Respuesta sinToken = llamar(operacion.metodo(), operacion.uri(), null, operacion.cuerpo());
                assertThat(sinToken.status())
                        .as("%s sin token", operacion.descripcion()).isEqualTo(401);
            }));
        }
        pasos.add(paso("Y el rechazo no filtra NADA del recurso protegido", () -> {
            // El 403 lo escribe la cadena de seguridad antes de llegar al controlador, así que el cuerpo
            // viene vacío. Lo que importa certificar es justo eso: ni datos ni pistas sobre el recurso.
            Respuesta r = llamar(HttpMethod.GET, "/api/admin/dashboard/metrics", tokenUsuarioNormal, null);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.cuerpo().has("gmvUsd")).isFalse();
            assertThat(r.cuerpo().has("totalOrders")).isFalse();
        }));
        return pasos;
    }

    /** Una operación sensible del panel, para recorrer la matriz de autorización sin repetir código. */
    private record Operacion(String descripcion, HttpMethod metodo, String uri, Object cuerpo) {
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  CASOS QUE NO SE PUEDEN EJECUTAR EN ESTA APLICACIÓN
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    @Test
    @Disabled("El canal de la regla de precio NO se puede fijar desde el panel: PriceRuleDtoIn no tiene"
            + " campo channel y el mapeador lo deja siempre en STOREFRONT. El margen del canal de"
            + " integración solo se puede sembrar por migración, así que desde la API de administración"
            + " este caso no tiene superficie que ejercitar.")
    @DisplayName("Configurar un margen distinto para el canal de integración")
    void margenPorCanalDeIntegracion() {
        // Sin implementación que ejercitar.
    }

    @Test
    @Disabled("La entrega real contra el transportista no se puede recorrer aquí: YunExpress está"
            + " desactivado y sin credenciales en la certificación, y el modo simulado que inventa la"
            + " guía solo se permite fuera de producción. Lo que sí se certifica es la máquina de estados"
            + " del pedido (remitir, en camino, entregar) y la comisión que la entrega acredita.")
    @DisplayName("Generar la guía real del transportista y seguir el envío contra su API")
    void guiaRealDelTransportista() {
        // Sin implementación que ejercitar.
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  PREPARACIÓN DEL ESCENARIO
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * Siembra lo que las migraciones dejarían puesto y el vaciado de tablas de {@code BaseIntegration}
     * se lleva por delante: divisas, cobertura de envío, impuestos, la regla aduanera de España, el
     * margen global y los actores (administrador, operador, cliente con saldo y usuario normal).
     *
     * <p>Las cachés en memoria (catálogo, divisas, reglas de margen) sobreviven al vaciado de tablas, de
     * modo que se limpian aquí y las de divisas y márgenes se refrescan por la vía del administrador,
     * que es la única que las invalida de verdad.
     */
    private void prepararPlataforma() {
        vaciarCaches();

        emailAdmin = "cert-admin-" + UUID.randomUUID() + "@example.com";
        idAdmin = UUID.randomUUID();
        crearUsuario(idAdmin, emailAdmin, "ADMIN");
        // Token de arranque para sembrar; el paso de acceso lo sustituye por uno obtenido de verdad.
        tokenAdmin = jwt.userToken(idAdmin, emailAdmin, "ADMIN");

        idOperador = UUID.randomUUID();
        crearUsuario(idOperador, "cert-operador@example.com", "OPERATOR");
        tokenOperador = jwt.userToken(idOperador, "cert-operador@example.com", "OPERATOR");

        idCliente = UUID.randomUUID();
        crearUsuario(idCliente, "cert-comprador@example.com", "USER");
        tokenCliente = jwt.userToken(idCliente, "cert-comprador@example.com", "USER");
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default,"
                + " status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", idCliente, SALDO_INICIAL);

        UUID idNormal = UUID.randomUUID();
        crearUsuario(idNormal, "cert-normal@example.com", "USER");
        tokenUsuarioNormal = jwt.userToken(idNormal, "cert-normal@example.com", "USER");

        insertarDivisa("USD", "Dólar", "$", "en-US", "1.00000000");
        insertarDivisa("CNY", "Yuan", "¥", "zh-CN", "8.00000000");
        insertarDivisa("EUR", "Euro", "€", "es-ES", "0.90000000");
        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/USD", tokenAdmin,
                Map.of("rateVsUsd", 1, "active", true)).status()).isEqualTo(200);

        insertarZona("ES", "España", "EU", 499, 350, 8, 18);
        insertarZona("US", "Estados Unidos", "AM", 799, 450, 10, 20);
        insertarImpuesto("ES", "IVA", IVA_ES_BPS);

        assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                reglaAduanera("DDP", 0, "SURCHARGE", DESPACHO_ES_CENTIMOS, 0, 0, 0)).status()).isEqualTo(200);

        // El ajuste por MOQ vive CACHEADO en memoria y sobrevive al vaciado de tablas: se fija en su
        // valor de fábrica para que el precio de partida no dependa de lo que dejara otra clase.
        assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                Map.of("enabled", true, "factorPercent", 50)).status()).isEqualTo(200);

        assertThat(llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 150, "active", true,
                        "position", 0, "description", "Certificación: margen global del escaparate"))
                .status()).isEqualTo(201);

        Respuesta categoria = llamar(HttpMethod.POST, "/api/admin/catalog/categories", tokenAdmin,
                Map.of("slug", SLUG_CATEGORIA, "nameZh", "认证", "position", 0,
                        "nameTranslations", Map.of("es", "Certificación", "en", "Certification")));
        assertThat(categoria.status()).isEqualTo(200);
        idCategoria = UUID.fromString(categoria.cuerpo().asText());

        vaciarCaches();
    }

    /** Producto de certificación: 80 CNY de coste, 16 de envío y 8 de IVA → 32,50 $ con el margen global. */
    private Map<String, Object> producto(String titulo, double precioCny, int moq) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("categorySlug", SLUG_CATEGORIA);
        // La importación exige proveedor: con el nombre lo crea al vuelo (y lo reutiliza si ya existe).
        cuerpo.put("supplierName", "Fábrica de certificación");
        if (titulo != null) {
            cuerpo.put("titleEs", titulo);
        }
        cuerpo.put("descriptionEs", "Artículo usado por la certificación de extremo a extremo.");
        // Nombre en inglés, nombre en chino y partida arancelaria: son los datos con los que se declara
        // en aduana y sin ellos el producto ya no se puede poner a la venta ni despachar su pedido.
        if (titulo != null) {
            cuerpo.put("titleEn", "Certification item");
            cuerpo.put("titleZh", "认证商品");
        }
        cuerpo.put("hsCode", "6109100000");
        cuerpo.put("price", precioCny);
        cuerpo.put("shippingCny", 16.0);
        cuerpo.put("ivaCny", 8.0);
        cuerpo.put("moq", moq);
        cuerpo.put("packageWeightGrams", 500);
        // Identificador externo EXPLÍCITO: es la clave del upsert. Sin él, la importación lo genera y
        // reimportar el mismo lote crearía productos nuevos en vez de actualizarlos.
        cuerpo.put("externalId", "CERT-" + Math.abs(String.valueOf(titulo).hashCode()));
        cuerpo.put("imageUrls", List.of("https://cert.local/" + (titulo == null ? "x" : titulo.hashCode())
                + ".jpg"));
        cuerpo.put("variantAxes", List.of(Map.of("name", "Color", "values", List.of("Rojo"))));
        cuerpo.put("variants", List.of(Map.of("sku", "CERT-" + Math.abs(String.valueOf(titulo).hashCode()),
                "optionValues", Map.of("Color", "Rojo"), "price", precioCny, "stock", 25,
                "packageWeightGrams", 500)));
        return cuerpo;
    }

    /** Promoción de administración: con {@code codigo} es un cupón; sin él, una rebaja automática. */
    private static Map<String, Object> promocion(String nombre, String codigo, BigDecimal porcentaje,
            Integer importeCentimos) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("name", nombre);
        cuerpo.put("code", codigo);
        cuerpo.put("kind", codigo == null ? "SEASONAL" : "COUPON");
        cuerpo.put("scope", "ALL");
        cuerpo.put("percentOff", porcentaje);
        cuerpo.put("amountOffCents", importeCentimos);
        cuerpo.put("active", true);
        return cuerpo;
    }

    /** Rebaja automática acotada al producto del recorrido. */
    private Map<String, Object> promocionProducto(String nombre, BigDecimal porcentaje) {
        Map<String, Object> cuerpo = promocion(nombre, null, porcentaje, null);
        cuerpo.put("scope", "PRODUCT");
        cuerpo.put("productIds", List.of(idProducto.toString()));
        return cuerpo;
    }

    private Map<String, Object> reglaAduanera(String modo, double umbral, String politica, int recargoFijo,
            int recargoBps, int recargoSobreUmbral, int arancelBps) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("taxMode", modo);
        cuerpo.put("deMinimisAmount", umbral);
        cuerpo.put("deMinimisCurrency", "USD");
        cuerpo.put("overThresholdPolicy", politica);
        cuerpo.put("handlingFeeCents", recargoFijo);
        cuerpo.put("handlingPercentBps", recargoBps);
        cuerpo.put("overThresholdSurchargeCents", recargoSobreUmbral);
        cuerpo.put("dutyRateBps", arancelBps);
        cuerpo.put("active", true);
        return cuerpo;
    }

    private void crearUsuario(UUID id, String email, String rol) {
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, active, language, display_name,"
                + " failed_login_count, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, true, 'es', ?, 0, now(), now())",
                id, email, passwordEncoder.encode(CLAVE_ADMIN), rol, "Cert " + rol);
    }

    private void insertarDivisa(String codigo, String nombre, String simbolo, String locale, String tasa) {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::numeric, true, now(), now())",
                codigo, nombre, simbolo, locale, tasa);
    }

    private void insertarZona(String pais, String nombre, String zona, int base, int porKg, int etaMin,
            int etaMax) {
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone, base_cents,"
                + " per_kg_cents, eta_min_days, eta_max_days, enabled, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, true, now(), now())",
                pais, nombre, zona, base, porKg, etaMin, etaMax);
    }

    private void insertarImpuesto(String pais, String etiqueta, int bps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active, created_at,"
                + " updated_at) VALUES (gen_random_uuid(), ?, ?, ?, true, now(), now())", pais, etiqueta, bps);
    }

    private long saldoCliente() {
        return jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?", Long.class,
                idCliente);
    }

    // vaciarCaches() vive ahora en BaseIntegration: lo necesitaba TODA la suite, no solo estas clases.
    // Vaciar la base sin vaciar las cachés dejaba pasar estado de una clase de prueba a la siguiente.

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  UTILIDADES
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    private record Respuesta(int status, JsonNode cuerpo) {
    }

    /**
     * Envuelve un paso del recorrido: si uno falla, los siguientes se OMITEN en vez de encadenar fallos
     * derivados, para que el informe señale el punto exacto de la rotura.
     */
    private DynamicTest paso(String nombre, Executable accion) {
        return DynamicTest.dynamicTest(nombre, () -> {
            Assumptions.assumeTrue(SEGUIR_TRAS_FALLO || !cadenaRota,
                    "paso omitido: la cadena se rompió en un paso anterior");
            try {
                accion.execute();
            } catch (Throwable error) {
                cadenaRota = true;
                throw error;
            }
        });
    }

    private Respuesta llamar(HttpMethod metodo, String uri, String token, Object cuerpo) {
        WebTestClient.RequestBodySpec peticion = client.method(metodo).uri(uri).header("X-Forwarded-For", IP_PROPIA);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        WebTestClient.ResponseSpec respuesta = cuerpo == null
                ? peticion.exchange()
                : peticion.contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange();
        EntityExchangeResult<byte[]> resultado = respuesta.expectBody().returnResult();
        return new Respuesta(resultado.getStatus().value(), interpretar(resultado.getResponseBody()));
    }

    /** Variante para cuerpos que no son JSON (la importación por líneas viaja como texto plano). */
    private Respuesta llamarTexto(HttpMethod metodo, String uri, String token, String cuerpo, String tipo) {
        EntityExchangeResult<byte[]> resultado = client.method(metodo).uri(uri)
                .header("X-Forwarded-For", IP_PROPIA)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.parseMediaType(tipo)).bodyValue(cuerpo)
                .exchange().expectBody().returnResult();
        return new Respuesta(resultado.getStatus().value(), interpretar(resultado.getResponseBody()));
    }

    /** Petición del escaparate con una moneda de visualización concreta (cabecera {@code X-Currency}). */
    private Respuesta llamarConMoneda(String uri, String moneda) {
        EntityExchangeResult<byte[]> resultado = client.get().uri(uri)
                .header("X-Forwarded-For", IP_PROPIA).header("X-Currency", moneda)
                .exchange().expectBody().returnResult();
        return new Respuesta(resultado.getStatus().value(), interpretar(resultado.getResponseBody()));
    }

    private static JsonNode interpretar(byte[] cuerpo) {
        if (cuerpo == null || cuerpo.length == 0) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.readTree(cuerpo);
        } catch (Exception noEsJson) {
            return MAPPER.getNodeFactory().textNode(new String(cuerpo, StandardCharsets.UTF_8));
        }
    }

    private static String escribirJson(Object valor) {
        try {
            return MAPPER.writeValueAsString(valor);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo serializar el cuerpo de la petición", e);
        }
    }

    /**
     * Comprueba un importe AL CÉNTIMO comparando el valor numérico, no el texto: 150 y 150.00 son el
     * mismo margen, pero 80.53 y 80.54 son dinero distinto.
     */
    private static void importeExacto(String concepto, JsonNode nodo, String esperado) {
        assertThat(nodo).as("%s: el importe no viene en la respuesta", concepto).isNotNull();
        assertThat(nodo.isNull()).as("%s: el importe llega nulo", concepto).isFalse();
        assertThat(new BigDecimal(nodo.asText())).as("%s", concepto)
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal(esperado));
    }

    /** Primer elemento de un array JSON cuyo campo {@code campo} vale {@code valor}. */
    private static JsonNode buscar(JsonNode array, String campo, String valor) {
        for (JsonNode elemento : array) {
            if (elemento.hasNonNull(campo) && valor.equals(elemento.get(campo).asText())) {
                return elemento;
            }
        }
        throw new AssertionError("no hay ningún elemento con " + campo + " = " + valor + " en " + array);
    }

    private static Map<String, Object> linea(UUID producto, UUID variante, int cantidad) {
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("productId", producto.toString());
        linea.put("variantId", variante == null ? null : variante.toString());
        linea.put("quantity", cantidad);
        return linea;
    }

    private String textoEnBd(String sql, Object parametro) {
        return jdbcTemplate.queryForObject(sql, String.class, parametro);
    }

    private int enteroEnBd(String sql, Object... parametros) {
        Integer valor = jdbcTemplate.queryForObject(sql, Integer.class, parametros);
        return valor == null ? 0 : valor;
    }
}
