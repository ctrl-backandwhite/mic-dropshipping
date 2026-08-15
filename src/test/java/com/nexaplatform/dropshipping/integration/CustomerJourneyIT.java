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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
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
 * CERTIFICACIÓN DE EXTREMO A EXTREMO — recorrido COMPLETO de un cliente normal.
 *
 * <p>No es una batería de casos sueltos: es UN recorrido encadenado en el que el estado que deja cada
 * paso es la entrada del siguiente (el código de activación abre la sesión, la sesión marca el
 * favorito, el carrito alimenta la cotización, la cotización debe coincidir al céntimo con el cobro, el
 * cobro deja un pedido que se factura, se sigue, se cancela y devuelve el saldo). Por eso va en un
 * {@link TestFactory}: {@code BaseIntegration} vacía la base ANTES DE CADA MÉTODO de test, así que un
 * recorrido repartido en varios {@code @Test} perdería el estado entre pasos. Con pasos dinámicos hay
 * una sola limpieza, un solo escenario y un nombre legible por paso en el informe.
 *
 * <p><b>Dinero.</b> Todo importe se comprueba EXACTO, calculado a mano. Los números del escenario se
 * eligen para que la cuenta sea verificable a ojo (tasa CNY 8,00 → 1 USD; margen global del 150 %):
 * <pre>
 *   coste     = 80,00 CNY / 8 = 10,0000 USD
 *   base      = 10,0000 × (1 + 150/100) = 25,0000 USD
 *   IVA prod. =  8,00 CNY / 8 =  1,0000 USD   (se suma SIN margen)
 *   envío pr. = 16,00 CNY / 8 =  2,0000 USD   (se suma SIN margen)
 *   precio unitario = 25,00 + 1,00 + 2,00 = 28,00 USD → 2.800 céntimos
 * </pre>
 *
 * <p><b>Alcance.</b> El recorrido cubre el doble factor REAL (alta, acceso con código de un solo uso,
 * código de respaldo de un único uso y anti-repetición), el cupón aplicado de punta a punta —desde que
 * la tienda lo emite hasta que queda canjeado en el pedido cobrado— y la lista de guardados ligada a la
 * cuenta, no al navegador.
 *
 * <p><b>Casos borde.</b> Cada bloque incluye sus límites (cero, uno, el máximo y el máximo + 1), el
 * valor EXACTO del borde de cada comparación, vacíos y nulos, la repetición (idempotencia), los estados
 * imposibles (operar sobre lo ya cancelado o ya consumido) y la autorización cruzada (el recurso de
 * otro usuario). Lo que la aplicación NO implementa se declara {@code @Disabled} con el motivo escrito,
 * nunca se omite en silencio.
 */
@DisplayName("Certificación e2e · recorrido completo del cliente")
class CustomerJourneyIT extends BaseIntegration {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * El filtro de tráfico limita por IP y sus cubos viven en memoria durante TODA la JVM: cinco altas
     * por hora, diez inicios de sesión por minuto y cien llamadas de escaparate por minuto. Con la IP
     * real (127.0.0.1) el recorrido competiría por esa cuota con cualquier otra clase de la misma
     * ejecución y fallaría por 429 sin que hubiera nada roto. Se declara una IP propia y aleatoria por
     * ejecución para que este recorrido tenga su cubo entero.
     */
    private static final String IP_PROPIA = "10." + ThreadLocalRandom.current().nextInt(1, 250) + "."
            + ThreadLocalRandom.current().nextInt(1, 250) + "." + ThreadLocalRandom.current().nextInt(1, 250);

    /**
     * Segunda IP, solo para el bloque de accesos. El limitador corta a los diez inicios de sesión por
     * minuto y por IP; separando el bloque del doble factor, la cuota del limitador no enmascara las
     * comprobaciones funcionales (y el propio limitador se certifica en su paso).
     */
    private static final String IP_ACCESOS = "10.77." + ThreadLocalRandom.current().nextInt(1, 250) + "."
            + ThreadLocalRandom.current().nextInt(1, 250);

    private static final String CONTRASENA = "CertCliente123!";
    private static final String CONTRASENA_NUEVA = "CertCliente456!";
    private static final String SLUG_CATEGORIA = "cert-recorrido-cliente";

    /* ── Importes del escenario, calculados a mano (céntimos USD) ───────────────────────────────── */

    /** Precio unitario de venta: 25,00 (base con margen) + 1,00 (IVA) + 2,00 (envío del producto). */
    private static final int UNIDAD_CENTIMOS = 2800;
    /** Tarifa del transportista a España: 4,99 fijos + 3,50/kg. */
    private static final int ENVIO_BASE_CENTIMOS = 499;
    private static final int ENVIO_POR_KG_CENTIMOS = 350;
    /** Recargo fijo del despacho DDP configurado para España. */
    private static final int DESPACHO_ES_CENTIMOS = 250;
    /** IVA español en puntos básicos. */
    private static final int IVA_ES_BPS = 2100;
    /** Impuesto de California, para comprobar que la tasa se resuelve por REGIÓN y no por país. */
    private static final int IMPUESTO_CA_BPS = 725;
    /** Saldo inicial del monedero: 200,00 USD. */
    private static final long SALDO_INICIAL = 20000L;

    @Autowired
    private CacheManager cacheManager;

    /* ── Estado del recorrido: lo que deja un paso lo consume el siguiente ──────────────────────── */

    /**
     * Modo diagnóstico. En false (lo normal) un paso roto OMITE los siguientes: el informe señala el
     * punto exacto donde se rompió la cadena en vez de sepultarlo bajo fallos derivados. Ponerlo a true
     * ejecuta el recorrido entero pase lo que pase, que es lo útil cuando se está depurando y se quiere
     * ver todos los puntos rotos de una sola pasada.
     */
    private static final boolean SEGUIR_TRAS_FALLO = false;

    private boolean cadenaRota;
    private String tokenAdmin;
    private String emailCliente;
    private UUID idCliente;
    private String tokenCliente;
    private String codigoActivacion;
    private UUID idProducto;
    private UUID idVariante;
    private String slugProducto;
    private UUID idDireccion;
    private UUID idPedido;
    private String secretTotp;
    private String otpUsado;
    private String tokenVisitante;
    private List<String> codigosRespaldo = List.of();
    private UUID idOtroCliente;
    private String tokenOtroCliente;
    private long totalPedidoCentimos;
    private long totalConCuponCentimos;

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  EL RECORRIDO
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    @TestFactory
    @DisplayName("El cliente recorre la tienda de principio a fin")
    List<DynamicTest> recorridoDelCliente() {
        prepararTienda();

        List<DynamicTest> pasos = new ArrayList<>();
        pasos.addAll(bloqueRegistroYActivacion());
        pasos.addAll(bloqueSesion());
        pasos.addAll(bloqueDobleFactor());
        pasos.addAll(bloqueCatalogo());
        pasos.addAll(bloqueFavoritos());
        pasos.addAll(bloqueCarrito());
        pasos.addAll(bloqueGuardarParaMasTarde());
        pasos.addAll(bloqueEnvioPorPais());
        pasos.addAll(bloqueDescuento());
        pasos.addAll(bloqueCupon());
        pasos.addAll(bloqueVistaPreviaYPago());
        pasos.addAll(bloquePedido());
        pasos.addAll(bloqueCancelacionYReembolso());
        pasos.addAll(bloqueBajaDeCuenta());
        return pasos;
    }

    /* ── 1. Registro y correo de activación ─────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueRegistroYActivacion() {
        return List.of(
                paso("Se registra con un correo nuevo y la cuenta nace DESACTIVADA", () -> {
                    emailCliente = "cert-cliente-" + UUID.randomUUID() + "@example.com";
                    Respuesta r = llamar(HttpMethod.POST, "/api/auth/register", null,
                            Map.of("email", emailCliente, "password", CONTRASENA, "firstName", "Ada",
                                    "lastName1", "Lovelace", "country", "ES", "language", "es",
                                    "acceptedTerms", true, "acceptedTermsVersion", "2026-07-31"));
                    assertThat(r.status()).as("alta correcta").isEqualTo(201);
                    idCliente = UUID.fromString(r.cuerpo().get("userId").asText());
                    // El estado REAL en base de datos, no solo el código HTTP: nace inactiva y con código.
                    assertThat(booleanoEnBd("SELECT active FROM users WHERE id = ?", idCliente))
                            .as("la cuenta recién creada no puede estar activa").isFalse();
                    codigoActivacion = textoEnBd("SELECT activation_code FROM users WHERE id = ?", idCliente);
                    assertThat(codigoActivacion).as("código de activación generado").isNotBlank();
                }),

                paso("El correo de activación queda encolado con el enlace que lleva el código", () -> {
                    // El correo no se envía en la petición: se encola. Es lo que hay que certificar —que
                    // existe, va al destinatario correcto y transporta el código que abrirá la cuenta.
                    Map<String, Object> correo = jdbcTemplate.queryForMap(
                            "SELECT to_address, subject, body_html, status FROM outbound_email"
                                    + " WHERE to_address = ? ORDER BY created_at DESC LIMIT 1", emailCliente);
                    assertThat(correo.get("to_address")).isEqualTo(emailCliente);
                    assertThat((String) correo.get("body_html"))
                            .as("el cuerpo del correo debe llevar el enlace con el código")
                            .contains("/activate?code=" + codigoActivacion);
                }),

                paso("Un alta repetida con el MISMO correo responde IGUAL que una buena, sin duplicar", () -> {
                    // Anti-enumeración: la respuesta es idéntica a la de un alta correcta (201 y un id
                    // efímero) para que nadie pueda distinguir "correo registrado" de "correo libre". Lo
                    // que se certifica es que el disfraz es completo Y que no se crea una segunda cuenta.
                    Respuesta r = llamar(HttpMethod.POST, "/api/auth/register", null,
                            Map.of("email", emailCliente, "password", CONTRASENA,
                                    "acceptedTerms", true, "acceptedTermsVersion", "2026-07-31"));
                    assertThat(r.status()).as("mismo status que un alta correcta").isEqualTo(201);
                    assertThat(r.cuerpo().get("userId").asText())
                            .as("el id devuelto es de usar y tirar, no el real")
                            .isNotEqualTo(idCliente.toString());
                    assertThat(enteroEnBd("SELECT count(*) FROM users WHERE email = ?", emailCliente))
                            .as("sigue habiendo UNA sola fila").isEqualTo(1);
                    assertThat(enteroEnBd("SELECT count(*) FROM outbound_email WHERE to_address = ?", emailCliente))
                            .as("y no se reenvía ningún correo al titular").isEqualTo(1);
                }),

                paso("Un alta con contraseña de 7 caracteres (uno por debajo del mínimo) se rechaza", () ->
                        // Valor límite: el mínimo son 8; con 7 la validación del DTO responde 400.
                        assertThat(llamar(HttpMethod.POST, "/api/auth/register", null,
                                Map.of("email", "cert-corta-" + UUID.randomUUID() + "@example.com",
                                        "password", "Ab1!efg")).status()).isEqualTo(400)),

                paso("Activar con un código inexistente no abre la cuenta", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/auth/activate", null,
                            Map.of("code", "codigo-que-no-existe")).status()).isEqualTo(422);
                    assertThat(booleanoEnBd("SELECT active FROM users WHERE id = ?", idCliente)).isFalse();
                }),

                paso("Activar con un código VACÍO se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/auth/activate", null,
                                Map.of("code", "")).status()).isEqualTo(400)),

                paso("Con el código real la cuenta queda activa y el código se consume", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/auth/activate", null,
                            Map.of("code", codigoActivacion)).status()).isEqualTo(204);
                    assertThat(booleanoEnBd("SELECT active FROM users WHERE id = ?", idCliente)).isTrue();
                    assertThat(textoEnBd("SELECT activation_code FROM users WHERE id = ?", idCliente))
                            .as("el código se borra al usarlo").isNull();
                }),

                paso("Reutilizar el código ya consumido no vuelve a activar (estado imposible)", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/auth/activate", null,
                                Map.of("code", codigoActivacion)).status()).isEqualTo(422)));
    }

    /* ── 2. Sesión ──────────────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueSesion() {
        return List.of(
                paso("Con contraseña incorrecta no se abre sesión", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/auth/login", null,
                                Map.of("email", emailCliente, "password", "NoEsLaMia1!")).status()).isEqualTo(401)),

                paso("Con las credenciales correctas entra y recibe su par de tokens", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA));
                    assertThat(r.status()).isEqualTo(200);
                    tokenCliente = r.cuerpo().get("token").asText();
                    assertThat(tokenCliente).isNotBlank();
                    assertThat(r.cuerpo().get("refreshToken").asText()).isNotBlank();
                    assertThat(r.cuerpo().get("tokenType").asText()).isEqualTo("Bearer");
                    assertThat(r.cuerpo().get("user").get("email").asText()).isEqualTo(emailCliente);
                }),

                paso("Su perfil responde con la identidad recién autenticada", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("email").asText()).isEqualTo(emailCliente);
                    assertThat(r.cuerpo().get("id").asText()).isEqualTo(idCliente.toString());
                }),

                paso("Sin token, el perfil responde vacío y NO filtra ningún dato", () -> {
                    // GET /api/me es, a propósito, la sonda de arranque del front: responde 200 aunque no
                    // haya sesión (así el SPA distingue "no logueado" de "servidor caído"). Lo que hay que
                    // certificar es que sin token NO sale ni un dato: cuerpo vacío.
                    Respuesta r = llamar(HttpMethod.GET, "/api/me", null, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().isNull()).as("sin sesión no puede viajar ningún perfil").isTrue();
                }),

                paso("Las rutas privadas de /api/me sí exigen token: sin él, 401", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/me/orders", null, null).status()).isEqualTo(401)));
    }

    /* ── 3. Doble factor: alta, comprobación y baja ─────────────────────────────────────────────── */

    private List<DynamicTest> bloqueDobleFactor() {
        return List.of(
                paso("Antes de darlo de alta, el doble factor figura desactivado", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("enabled").asBoolean()).isFalse();
                }),

                paso("El alta devuelve el secreto y la URL del código QR", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/2fa/setup", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    secretTotp = r.cuerpo().get("base32Secret").asText();
                    // 20 bytes en base32 son 32 caracteres: el secreto tiene el tamaño del estándar.
                    assertThat(secretTotp).hasSize(32);
                    assertThat(r.cuerpo().get("otpauthUrl").asText())
                            .startsWith("otpauth://totp/").contains("secret=" + secretTotp);
                }),

                paso("Un código de un dígito de más (siete) no activa el doble factor", () -> {
                    // El DTO solo exige que no venga vacío; la longitud la valida el algoritmo, así que un
                    // código de siete dígitos se rechaza como regla de negocio (422) y no por validación.
                    assertThat(llamar(HttpMethod.POST, "/api/me/2fa/verify", tokenCliente,
                            Map.of("otp", "1234567")).status()).isEqualTo(422);
                    assertThat(llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null)
                            .cuerpo().get("enabled").asBoolean()).isFalse();
                }),

                paso("Un código VACÍO se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/2fa/verify", tokenCliente,
                                Map.of("otp", "")).status()).isEqualTo(400)),

                paso("Un código de seis dígitos pero equivocado no activa el doble factor", () -> {
                    // "000000" solo sería válido por casualidad; se calcula el correcto y se envía OTRO.
                    String correcto = totp(secretTotp, System.currentTimeMillis() / 1000);
                    String incorrecto = correcto.equals("000000") ? "999999" : "000000";
                    assertThat(llamar(HttpMethod.POST, "/api/me/2fa/verify", tokenCliente,
                            Map.of("otp", incorrecto)).status()).isEqualTo(422);
                    assertThat(llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null)
                            .cuerpo().get("enabled").asBoolean()).isFalse();
                }),

                paso("Con el código correcto se activa y entrega DIEZ códigos de respaldo únicos", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/2fa/verify", tokenCliente,
                            Map.of("otp", totp(secretTotp, System.currentTimeMillis() / 1000)));
                    assertThat(r.status()).isEqualTo(200);
                    codigosRespaldo = new ArrayList<>();
                    r.cuerpo().get("backupCodes").forEach(n -> codigosRespaldo.add(n.asText()));
                    assertThat(codigosRespaldo).hasSize(10).doesNotHaveDuplicates();
                    assertThat(llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null)
                            .cuerpo().get("enabled").asBoolean()).isTrue();
                }),

                paso("Repetir el alta con el doble factor ya activo se rechaza", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/2fa/setup", tokenCliente, null).status())
                                .isEqualTo(422)),

                paso("Con el doble factor activo, entrar SIN código se rechaza pidiéndolo", () -> {
                    // El primer factor es correcto: la contraseña es la suya. Aun así no se entra, y el
                    // motivo se distingue del de credenciales malas para que el front pida el código.
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA));
                    assertThat(r.status()).isEqualTo(401);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("MFA_REQUIRED");
                }),

                paso("Con un código de un solo uso equivocado tampoco se entra", () -> {
                    String correcto = totp(secretTotp, System.currentTimeMillis() / 1000);
                    String incorrecto = correcto.equals("000000") ? "999999" : "000000";
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA, "otp", incorrecto));
                    assertThat(r.status()).isEqualTo(401);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("MFA_INVALID");
                }),

                paso("Con la contraseña Y el código correcto sí entra", () -> {
                    // Se usa el código del step SIGUIENTE (30 s por delante, dentro de la tolerancia de
                    // deriva): el del step actual ya se gastó al dar de alta el doble factor y el
                    // anti-repetición lo rechazaría. Eso se certifica justo debajo.
                    otpUsado = totp(secretTotp, System.currentTimeMillis() / 1000 + 30);
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA, "otp", otpUsado));
                    assertThat(r.status()).isEqualTo(200);
                    tokenCliente = r.cuerpo().get("token").asText();
                    assertThat(tokenCliente).isNotBlank();
                }),

                paso("Ese MISMO código ya no vale una segunda vez: no hay repetición posible", () -> {
                    // Anti-repetición: aunque el código siga dentro de su ventana de 30 s, su contador ya
                    // está consumido. Sin esto, quien viera el código por encima del hombro entraría.
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA, "otp", otpUsado));
                    assertThat(r.status()).isEqualTo(401);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("MFA_INVALID");
                }),

                paso("Si pierde el móvil, un código de respaldo también le abre la sesión", () -> {
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA,
                                    "otp", codigosRespaldo.get(0)));
                    assertThat(r.status()).isEqualTo(200);
                    tokenCliente = r.cuerpo().get("token").asText();
                }),

                paso("Ese código de respaldo es de UN SOLO uso: repetirlo ya no vale", () -> {
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA,
                                    "otp", codigosRespaldo.get(0)));
                    assertThat(r.status()).isEqualTo(401);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("MFA_INVALID");
                }),

                paso("Regenerar los códigos de respaldo invalida el juego anterior", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/2fa/backup-codes/regenerate", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    List<String> nuevos = new ArrayList<>();
                    r.cuerpo().get("backupCodes").forEach(n -> nuevos.add(n.asText()));
                    assertThat(nuevos).hasSize(10).doesNotContainAnyElementsOf(codigosRespaldo);
                    codigosRespaldo = nuevos;
                }),

                paso("La baja del doble factor con contraseña equivocada se rechaza", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/me/2fa/disable", tokenCliente,
                            Map.of("password", "NoEsLaMia1!")).status()).isEqualTo(422);
                    assertThat(llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null)
                            .cuerpo().get("enabled").asBoolean()).isTrue();
                }),

                paso("Con su contraseña, la baja del doble factor sí se completa", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/me/2fa/disable", tokenCliente,
                            Map.of("password", CONTRASENA)).status()).isEqualTo(204);
                    assertThat(llamar(HttpMethod.GET, "/api/me/2fa/status", tokenCliente, null)
                            .cuerpo().get("enabled").asBoolean()).isFalse();
                    assertThat(enteroEnBd("SELECT count(*) FROM totp_secret WHERE user_id = ?", idCliente))
                            .as("el secreto se borra de la base al dar de baja").isZero();
                }),

                paso("Dada de baja, regenerar códigos de respaldo ya no es posible", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/2fa/backup-codes/regenerate", tokenCliente, null)
                                .status()).isEqualTo(404)),

                paso("Y sin doble factor vuelve a entrar solo con su contraseña", () -> {
                    Respuesta r = llamarDesde(IP_ACCESOS, HttpMethod.POST, "/api/auth/login", null,
                            Map.of("email", emailCliente, "password", CONTRASENA));
                    assertThat(r.status()).isEqualTo(200);
                    tokenCliente = r.cuerpo().get("token").asText();
                }),

                paso("El limitador corta la fuerza bruta: el intento 11 en un minuto ya no se atiende", () -> {
                    // Diez intentos por minuto y por IP. Se usa una IP de usar y tirar para no gastar la
                    // cuota del recorrido, que es justo lo que el limitador está para impedir.
                    String ipAtacante = "10.66.66." + ThreadLocalRandom.current().nextInt(1, 250);
                    int ultimoStatus = 0;
                    for (int intento = 1; intento <= 11; intento++) {
                        ultimoStatus = llamarDesde(ipAtacante, HttpMethod.POST, "/api/auth/login", null,
                                Map.of("email", emailCliente, "password", "NoEsLaMia1!")).status();
                    }
                    assertThat(ultimoStatus).as("el intento que pasa del tope se corta con 429")
                            .isEqualTo(429);
                }));
    }

    /* ── 4. Catálogo, búsqueda y ficha ──────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueCatalogo() {
        return List.of(
                paso("El listado del escaparate ya NO es anónimo: sin sesión responde 401", () ->
                        // Anti-clonado: volcar el catálogo entero exige estar identificado. La ficha suelta
                        // sigue siendo pública (se comprueba más abajo), que es lo que necesita el SEO.
                        assertThat(llamar(HttpMethod.GET, "/api/catalog/products?lang=es", null, null).status())
                                .isEqualTo(401)),

                paso("El escaparate lista el producto con su precio EXACTO de 28,00 $", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&size=24",
                            tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                    JsonNode ficha = r.cuerpo().get("items").get(0);
                    assertThat(ficha.get("id").asText()).isEqualTo(idProducto.toString());
                    assertThat(ficha.get("title").asText()).isEqualTo("Camiseta de certificación");
                    // 80 CNY / 8 = 10 USD de coste → ×2,5 por el margen = 25 → +1 IVA +2 envío = 28,00.
                    importeExacto("precio mostrado en el listado", ficha.get("displayPrice"), "28.00");
                    assertThat(ficha.get("displayCurrency").asText()).isEqualTo("USD");
                    assertThat(ficha.get("displayFormatted").asText()).isEqualTo("$28.00");
                }),

                paso("La segunda página del listado viene vacía pero con el total correcto", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&page=1&size=24", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("items")).isEmpty();
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                }),

                paso("Buscar por una palabra del título encuentra el producto", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&q=camiseta", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                }),

                paso("Buscar una palabra que no existe devuelve un listado vacío, no un error", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/catalog/products?lang=es&q=zzzznoexiste", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("totalElements").asInt()).isZero();
                }),

                paso("El buscador global responde aunque el motor de búsqueda no esté (degradación limpia)",
                        () -> {
                            // La certificación corre a propósito SIN motor de búsqueda (ver la anotación de
                            // la clase): lo que se exige es que el endpoint no reviente y devuelva un
                            // resultado vacío bien formado en vez de un 500 en la cara del cliente. La
                            // búsqueda funcional del escaparate, que va por SQL, se certifica arriba.
                            Respuesta r = llamar(HttpMethod.GET, "/api/search?q=camiseta&lang=es", tokenCliente, null);
                            assertThat(r.status()).isEqualTo(200);
                            assertThat(r.cuerpo().get("total").asLong()).isZero();
                            assertThat(r.cuerpo().get("items")).isEmpty();
                        }),

                paso("La ficha del producto trae su variante y el mismo precio que el listado", () -> {
                    Respuesta r = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("slug").asText()).isEqualTo(slugProducto);
                    assertThat(r.cuerpo().get("moq").asInt()).isEqualTo(1);
                    importeExacto("precio mostrado en la ficha", r.cuerpo().get("displayPrice"), "28.00");
                    assertThat(r.cuerpo().get("displayFormatted").asText()).isEqualTo("$28.00");
                    assertThat(r.cuerpo().get("variants")).hasSize(1);
                    idVariante = UUID.fromString(r.cuerpo().get("variants").get(0).get("id").asText());
                    assertThat(r.cuerpo().get("variants").get(0).get("active").asBoolean()).isTrue();
                }),

                paso("La ficha NO le filtra al cliente el coste, el margen ni el desglose del precio", () -> {
                    // Es lo que separa el precio de venta de la información interna: coste de compra,
                    // porcentaje de margen y descomposición base/IVA/envío son solo para administración.
                    Respuesta r = llamar(HttpMethod.GET,
                            "/api/catalog/products/" + slugProducto + "?lang=es", null, null);
                    for (String campoInterno : List.of("costUsd", "retailUsd", "appliedMarginPercent",
                            "baseFormatted", "ivaFormatted", "shippingFormatted")) {
                        assertThat(r.cuerpo().get(campoInterno).isNull())
                                .as("%s no puede salir al cliente", campoInterno).isTrue();
                    }
                }),

                paso("Una ficha con un identificador inventado responde 404", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/catalog/products/no-existe-este-slug?lang=es",
                                null, null).status()).isEqualTo(404)));
    }

    /* ── 5. Favoritos (la lista "para más tarde" que sí existe) ─────────────────────────────────── */

    private List<DynamicTest> bloqueFavoritos() {
        return List.of(
                paso("Marca el producto como favorito", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/favorites/" + idProducto, tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("favorite").asBoolean()).isTrue();
                    assertThat(enteroEnBd("SELECT count(*) FROM product_favorite WHERE user_id = ?", idCliente))
                            .isEqualTo(1);
                }),

                paso("Marcarlo dos veces no lo duplica (idempotencia)", () -> {
                    assertThat(llamar(HttpMethod.POST, "/api/me/favorites/" + idProducto, tokenCliente, null)
                            .cuerpo().get("favorite").asBoolean()).isTrue();
                    assertThat(enteroEnBd("SELECT count(*) FROM product_favorite WHERE user_id = ?", idCliente))
                            .as("sigue habiendo un único favorito").isEqualTo(1);
                }),

                paso("Su lista de favoritos devuelve exactamente ese producto", () -> {
                    Respuesta ids = llamar(HttpMethod.GET, "/api/me/favorites/ids", tokenCliente, null);
                    assertThat(ids.status()).isEqualTo(200);
                    assertThat(ids.cuerpo()).hasSize(1);
                    assertThat(ids.cuerpo().get(0).asText()).isEqualTo(idProducto.toString());

                    Respuesta lista = llamar(HttpMethod.GET, "/api/me/favorites?lang=es", tokenCliente, null);
                    assertThat(lista.cuerpo().get("totalElements").asInt()).isEqualTo(1);
                    importeExacto("precio en la lista de favoritos",
                            lista.cuerpo().get("items").get(0).get("displayPrice"), "28.00");
                }),

                paso("Los favoritos de otro cliente no se mezclan con los suyos", () -> {
                    // Autorización cruzada por diseño: la lista se resuelve por el token, no por parámetro.
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/favorites/ids", tokenOtroCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).as("el otro cliente no ve el favorito ajeno").isEmpty();
                }),

                paso("Quitar el favorito lo elimina, y quitarlo otra vez no falla", () -> {
                    assertThat(llamar(HttpMethod.DELETE, "/api/me/favorites/" + idProducto, tokenCliente, null)
                            .cuerpo().get("favorite").asBoolean()).isFalse();
                    assertThat(llamar(HttpMethod.DELETE, "/api/me/favorites/" + idProducto, tokenCliente, null)
                            .cuerpo().get("favorite").asBoolean()).as("borrar dos veces es idempotente").isFalse();
                    assertThat(enteroEnBd("SELECT count(*) FROM product_favorite WHERE user_id = ?", idCliente))
                            .isZero();
                }),

                paso("Lo vuelve a marcar: se lo lleva al carrito desde su lista", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/favorites/" + idProducto, tokenCliente, null)
                                .cuerpo().get("favorite").asBoolean()).isTrue()));
    }

    /* ── 6. Carrito ─────────────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueCarrito() {
        return List.of(
                paso("Añade DOS unidades al carrito y el subtotal es exactamente 56,00 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/catalog/cart-quote", tokenCliente,
                            List.of(linea(idProducto, idVariante, 2)));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("currency").asText()).isEqualTo("USD");
                    assertThat(r.cuerpo().get("items")).hasSize(1);
                    importeExacto("precio unitario del carrito", r.cuerpo().get("items").get(0).get("unit"), "28.00");
                    importeExacto("total de la línea", r.cuerpo().get("items").get(0).get("lineTotal"), "56.00");
                    // 2.800 × 2 = 5.600 céntimos. Ni uno más.
                    importeExacto("subtotal del carrito", r.cuerpo().get("subtotal"), "56.00");
                    assertThat(r.cuerpo().get("subtotalFormatted").asText()).isEqualTo("$56.00");
                }),

                paso("Con UNA unidad (el mínimo) el subtotal es el precio unitario", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/catalog/cart-quote", tokenCliente,
                            List.of(linea(idProducto, idVariante, 1)));
                    importeExacto("subtotal de una unidad", r.cuerpo().get("subtotal"), "28.00");
                }),

                paso("Un carrito VACÍO cotiza a cero sin romperse", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/catalog/cart-quote", tokenCliente, List.of());
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("items")).isEmpty();
                    importeExacto("subtotal de un carrito vacío", r.cuerpo().get("subtotal"), "0.00");
                }),

                paso("Una línea de un producto que ya no existe se descarta y no suma", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/catalog/cart-quote", tokenCliente,
                            List.of(linea(idProducto, idVariante, 1), linea(UUID.randomUUID(), null, 5)));
                    assertThat(r.cuerpo().get("items")).as("solo cotiza la línea vendible").hasSize(1);
                    importeExacto("subtotal ignorando la línea rota", r.cuerpo().get("subtotal"), "28.00");
                }),

                paso("Recupera el carrito: volver a cotizarlo da EXACTAMENTE el mismo importe", () -> {
                    // El carrito activo vive en el navegador; lo que la plataforma garantiza es que
                    // re-cotizarlo no cambia el precio mientras no cambie el catálogo.
                    Respuesta r = llamar(HttpMethod.POST, "/api/catalog/cart-quote", tokenCliente,
                            List.of(linea(idProducto, idVariante, 2)));
                    importeExacto("subtotal al recuperar el carrito", r.cuerpo().get("subtotal"), "56.00");
                }));
    }

    /* ── 6-bis. Guardar para más tarde (lista ligada a la cuenta, no al navegador) ──────────────── */

    private List<DynamicTest> bloqueGuardarParaMasTarde() {
        return List.of(
                paso("Su lista de guardados nace vacía", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/saved-cart", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).isEmpty();
                }),

                paso("Guarda el producto para más tarde y la respuesta trae la lista entera", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/me/saved-cart", tokenCliente, lineaGuardada(1));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    JsonNode guardado = r.cuerpo().get(0);
                    assertThat(guardado.get("productId").asText()).isEqualTo(idProducto.toString());
                    assertThat(guardado.get("quantity").asInt()).isEqualTo(1);
                    assertThat(guardado.get("sourceCurrency").asText()).isEqualTo("CNY");
                    importeExacto("precio de proveedor congelado", guardado.get("unitPriceSource"), "80.0000");
                }),

                paso("Guardar la MISMA línea otra vez SUMA cantidad en vez de duplicarla", () -> {
                    Respuesta r = llamar(HttpMethod.PUT, "/api/me/saved-cart", tokenCliente, lineaGuardada(2));
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("quantity").asInt()).as("1 + 2 = 3").isEqualTo(3);
                    assertThat(enteroEnBd("SELECT count(*) FROM saved_cart_item WHERE user_id = ?", idCliente))
                            .isEqualTo(1);
                }),

                paso("La lista sobrevive a la sesión: es de la CUENTA, no del navegador", () -> {
                    // Se lee con un token recién emitido para el mismo usuario: si viviera en el cliente,
                    // aquí no habría nada. Esto es lo que permite seguir la compra desde otro dispositivo.
                    String otroDispositivo = jwt.userToken(idCliente, emailCliente, "USER");
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/saved-cart", otroDispositivo, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("quantity").asInt()).isEqualTo(3);
                }),

                paso("Guardar CERO unidades se rechaza por validación", () -> {
                    Map<String, Object> cero = lineaGuardada(1);
                    cero.put("quantity", 0);
                    assertThat(llamar(HttpMethod.PUT, "/api/me/saved-cart", tokenCliente, cero).status())
                            .isEqualTo(400);
                }),

                paso("Guardar una unidad por encima del tope (100.001) se rechaza por validación", () -> {
                    Map<String, Object> pasado = lineaGuardada(1);
                    pasado.put("quantity", 100_001);
                    assertThat(llamar(HttpMethod.PUT, "/api/me/saved-cart", tokenCliente, pasado).status())
                            .isEqualTo(400);
                }),

                paso("Guardar un producto que no existe responde 404", () -> {
                    Map<String, Object> fantasma = lineaGuardada(1);
                    fantasma.put("productId", UUID.randomUUID().toString());
                    Respuesta r = llamar(HttpMethod.PUT, "/api/me/saved-cart", tokenCliente, fantasma);
                    assertThat(r.status()).isEqualTo(404);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("ENF001");
                }),

                paso("Al entrar, el carrito del invitado se FUNDE con lo que ya tenía guardado", () -> {
                    // Es el caso real: navegaba sin sesión, tenía una unidad en el carrito local y al
                    // identificarse no puede perderla ni pisar lo que ya había guardado: se suman.
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/saved-cart/merge", tokenCliente,
                            List.of(lineaGuardada(2)));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).hasSize(1);
                    assertThat(r.cuerpo().get(0).get("quantity").asInt()).as("3 + 2 = 5").isEqualTo(5);
                }),

                paso("Fundir una lista VACÍA no cambia nada", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/saved-cart/merge", tokenCliente, List.of());
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get(0).get("quantity").asInt()).isEqualTo(5);
                }),

                paso("La lista de otro cliente no se mezcla con la suya", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/saved-cart", tokenOtroCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).isEmpty();
                }),

                paso("Quita el guardado y la lista se queda vacía", () -> {
                    Respuesta r = llamar(HttpMethod.DELETE,
                            "/api/me/saved-cart/" + idProducto + "?variantId=" + idVariante, tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo()).isEmpty();
                    assertThat(enteroEnBd("SELECT count(*) FROM saved_cart_item WHERE user_id = ?", idCliente))
                            .isZero();
                }),

                paso("Quitarlo otra vez no falla (idempotente)", () ->
                        assertThat(llamar(HttpMethod.DELETE,
                                "/api/me/saved-cart/" + idProducto + "?variantId=" + idVariante, tokenCliente,
                                null).status()).isEqualTo(200)));
    }

    /* ── 7. Envío por país ──────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueEnvioPorPais() {
        return List.of(
                paso("Cotiza a España: 8,49 $ de porte más 2,50 $ de despacho = 10,99 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("supported").asBoolean()).isTrue();
                    // 2 unidades × 500 g = 1 kg → 499 + 350×1 = 849; más 250 de despacho DDP = 1.099.
                    int envioEsperado = ENVIO_BASE_CENTIMOS + ENVIO_POR_KG_CENTIMOS + DESPACHO_ES_CENTIMOS;
                    assertThat(envioEsperado).isEqualTo(1099);
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isEqualTo(envioEsperado);
                    assertThat(r.cuerpo().get("taxRateBps").asInt()).isEqualTo(IVA_ES_BPS);
                    assertThat(r.cuerpo().get("shippingFormatted").asText()).isEqualTo("$10.99");
                    assertThat(r.cuerpo().get("taxMode").asText()).isEqualTo("DDP");
                    assertThat(r.cuerpo().get("customsThresholdExceeded").asBoolean()).isFalse();
                }),

                paso("Cotiza a Estados Unidos con California: la tasa se resuelve por REGIÓN", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "US", "region", "CA",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("supported").asBoolean()).isTrue();
                    // Estados Unidos: 7,99 fijos + 4,50/kg, sin regla aduanera → 799 + 450 = 1.249.
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isEqualTo(1249);
                    assertThat(r.cuerpo().get("taxRateBps").asInt())
                            .as("manda la tasa de California, no la nacional").isEqualTo(IMPUESTO_CA_BPS);
                }),

                paso("Sin región, Estados Unidos cae a la tasa NACIONAL", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "US", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("taxRateBps").asInt()).isEqualTo(400);
                }),

                paso("A un destino desactivado no se envía y el importe es cero", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "JP", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("supported").asBoolean()).isFalse();
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isZero();
                }),

                paso("Con el país VACÍO tampoco hay envío, y no es un error del servidor", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "", "items", List.of(linea(idProducto, idVariante, 1))));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("supported").asBoolean()).isFalse();
                }),

                paso("La cobertura publica España y Estados Unidos, y NUNCA el destino desactivado", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/shipping/countries", null, null);
                    assertThat(r.status()).isEqualTo(200);
                    List<String> paises = new ArrayList<>();
                    r.cuerpo().forEach(n -> paises.add(n.get("countryCode").asText()));
                    assertThat(paises).containsExactlyInAnyOrder("ES", "US");
                }),

                paso("El peso del bulto escala el porte: 1 unidad (500 g) paga menos que 2 (1 kg)", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 1))));
                    // 0,5 kg → 499 + round(350 × 0,5) = 499 + 175 = 674; más 250 de despacho = 924.
                    assertThat(r.cuerpo().get("amountUsdCents").asInt()).isEqualTo(924);
                }));
    }

    /* ── 8. Descuento aplicado al carrito ───────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueDescuento() {
        return List.of(
                paso("Sin ninguna atribución, el descuento del carrito es CERO", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                    assertThat(r.cuerpo().get("discountFormatted").asText()).isEqualTo("$0.00");
                }),

                paso("El descuento de referido es del 10 % EXACTO, y exige que el afiliado esté APROBADO",
                        () -> {
                    // Alta de afiliado: queda PENDIENTE hasta que un administrador la aprueba, así que un
                    // clic con su código todavía no atribuye nada. Se certifica ese paso intermedio antes
                    // de comprobar el descuento.
                    Respuesta alta = llamar(HttpMethod.POST, "/api/me/affiliate/join", tokenOtroCliente, null);
                    assertThat(alta.status()).isEqualTo(200);
                    assertThat(alta.cuerpo().get("status").asText())
                            .as("recién solicitado no puede estar activo").isEqualTo("PENDING");
                    String codigo = alta.cuerpo().get("codes").get(0).get("code").asText();
                    assertThat(codigo).isNotBlank();

                    Map<String, Object> clicPrematuro = new LinkedHashMap<>();
                    clicPrematuro.put("ref", codigo);
                    clicPrematuro.put("visitorToken", null);
                    assertThat(llamar(HttpMethod.POST, "/api/affiliate/track", null, clicPrematuro)
                            .cuerpo().get("attributed").asBoolean())
                            .as("un afiliado sin aprobar no atribuye").isFalse();

                    UUID idAfiliado = UUID.fromString(alta.cuerpo().get("id").asText());
                    assertThat(llamar(HttpMethod.POST, "/api/admin/affiliates/" + idAfiliado + "/status",
                            tokenAdmin, Map.of("status", "ACTIVE")).status()).isEqualTo(204);

                    Map<String, Object> clic = new LinkedHashMap<>();
                    clic.put("ref", codigo);
                    clic.put("visitorToken", null);
                    Respuesta seguimiento = llamar(HttpMethod.POST, "/api/affiliate/track", null, clic);
                    assertThat(seguimiento.status()).isEqualTo(200);
                    assertThat(seguimiento.cuerpo().get("attributed").asBoolean()).isTrue();
                    String visitante = seguimiento.cuerpo().get("visitorToken").asText();
                    tokenVisitante = visitante;

                    assertThat(llamar(HttpMethod.POST, "/api/me/affiliate/bind", tokenCliente,
                            Map.of("visitorToken", visitante)).status()).isEqualTo(204);

                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    // 10 % de 5.600 = 560 céntimos exactos.
                    assertThat(r.cuerpo().get("discountCents").asInt()).isEqualTo(560);
                    assertThat(r.cuerpo().get("discountFormatted").asText()).isEqualTo("$5.60");
                }),

                paso("Un código de referido inexistente no atribuye nada", () -> {
                    Map<String, Object> clic = new LinkedHashMap<>();
                    clic.put("ref", "CODIGO-QUE-NO-EXISTE");
                    clic.put("visitorToken", null);
                    Respuesta r = llamar(HttpMethod.POST, "/api/affiliate/track", null, clic);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("attributed").asBoolean()).isFalse();
                }),

                paso("Se retira la atribución para que el cupón compita en igualdad", () -> {
                    // El descuento de referido ya está certificado al céntimo. Se retira porque el cupón NO
                    // se suma al referido: compite con él y gana el mayor, y eso se certifica aparte.
                    jdbcTemplate.update("UPDATE affiliate_attribution SET referred_user_id = NULL"
                            + " WHERE referred_user_id = ?", idCliente);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                }));
    }

    /* ── 8-bis. Cupones ─────────────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueCupon() {
        return List.of(
                paso("La tienda emite un cupón del 10 % para el recorrido", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            cupon("CERT10", new BigDecimal("10"), null));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("CERT10");
                    assertThat(r.cuerpo().get("usedCount").asInt()).isZero();
                    assertThat(r.cuerpo().get("live").asBoolean()).isTrue();
                }),

                paso("Con el cupón puesto, el carrito descuenta 5,60 $ EXACTOS y el total baja a 73,76 $",
                        () -> {
                            // 10 % de 5.600 = 560 (truncado a la baja, a favor de la tienda).
                            // Base imponible = (5.600 − 560) + 849 = 5.889 → IVA 21 % = 1.236,69 → 1.237.
                            // Total = 5.040 + (849 + 250) + 1.237 = 7.376.
                            Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                                    Map.of("country", "ES", "couponCode", "CERT10",
                                            "items", List.of(linea(idProducto, idVariante, 2))));
                            assertThat(r.status()).isEqualTo(200);
                            assertThat(r.cuerpo().get("couponCode").asText()).isEqualTo("CERT10");
                            assertThat(r.cuerpo().get("couponError").isNull()).isTrue();
                            assertThat(r.cuerpo().get("discountCents").asInt()).isEqualTo(560);
                            assertThat(r.cuerpo().get("discountFormatted").asText()).isEqualTo("$5.60");
                            assertThat(r.cuerpo().get("taxFormatted").asText()).isEqualTo("$12.37");
                            assertThat(r.cuerpo().get("totalFormatted").asText()).isEqualTo("$73.76");
                            totalConCuponCentimos = 7376L;
                        }),

                paso("El porcentaje se trunca a la BAJA: 3,33 % de 56,00 $ son 1,86 $ y no 1,87 $", () -> {
                    // 3,33 × 5.600 / 100 = 186,48 → el redondeo es DOWN, así que el cliente recibe 186.
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            cupon("CERTFRAC", new BigDecimal("3.33"), null)).status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTFRAC",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("discountCents").asInt()).isEqualTo(186);
                    assertThat(r.cuerpo().get("discountFormatted").asText()).isEqualTo("$1.86");
                }),

                paso("Un cupón de importe fijo MAYOR que el carrito se topa en el propio carrito", () -> {
                    // Valor límite: 999,00 $ de descuento sobre 56,00 $ de producto no puede dejar el
                    // subtotal en negativo; el descuento se recorta al subtotal exacto.
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            cupon("CERTMAX", null, 99900)).status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTMAX",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("discountCents").asInt()).isEqualTo(UNIDAD_CENTIMOS * 2);
                    // El producto sale gratis, pero el envío y su IVA se siguen cobrando: la base
                    // imponible queda en 0 + 849 de porte → IVA 178,29 → 178; total 0 + 1.099 + 178 = 1.277.
                    assertThat(r.cuerpo().get("totalFormatted").asText()).isEqualTo("$12.77");
                }),

                paso("Un cupón que no existe no descuenta y lo dice sin romper el carrito", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "NO-EXISTE-9999",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("couponCode").isNull()).isTrue();
                    assertThat(r.cuerpo().get("couponError").asText()).isEqualTo("Ese código no existe");
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                    assertThat(r.cuerpo().get("totalFormatted").asText())
                            .as("el total es el de siempre").isEqualTo("$80.53");
                }),

                paso("Un cupón CADUCADO se rechaza con su motivo", () -> {
                    Map<String, Object> caducado = cupon("CERTVIEJO", new BigDecimal("50"), null);
                    caducado.put("startsAt", Instant.now().minus(10, ChronoUnit.DAYS).toString());
                    caducado.put("endsAt", Instant.now().minus(1, ChronoUnit.DAYS).toString());
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, caducado).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTVIEJO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").asText()).isEqualTo("Ese cupón ha caducado");
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                }),

                paso("Un cupón que aún NO ha empezado tampoco vale", () -> {
                    Map<String, Object> futuro = cupon("CERTFUTURO", new BigDecimal("50"), null);
                    futuro.put("startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
                    futuro.put("endsAt", Instant.now().plus(10, ChronoUnit.DAYS).toString());
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, futuro).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTFUTURO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").asText())
                            .isEqualTo("Ese cupón todavía no ha empezado");
                }),

                paso("Un cupón AGOTADO se rechaza con su motivo", () -> {
                    // Valor límite del contador: con cero usos permitidos ya está agotado en el uso cero.
                    Map<String, Object> agotado = cupon("CERTAGOTADO", new BigDecimal("50"), null);
                    agotado.put("maxUses", 0);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, agotado).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTAGOTADO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").asText()).isEqualTo("Ese cupón se ha agotado");
                }),

                paso("Un cupón con pedido mínimo por encima del carrito no se puede usar", () -> {
                    // Borde exacto: el carrito son 5.600 y el mínimo 5.601 → un céntimo de menos.
                    Map<String, Object> minimo = cupon("CERTMINIMO", new BigDecimal("50"), null);
                    minimo.put("minOrderCents", UNIDAD_CENTIMOS * 2 + 1);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, minimo).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTMINIMO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").asText()).startsWith("Ese cupón necesita un pedido");
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                }),

                paso("Con el mínimo EXACTAMENTE igual al carrito, el cupón sí entra", () -> {
                    // El otro lado del borde: 5.600 no es menor que 5.600, así que la condición no salta.
                    Map<String, Object> justo = cupon("CERTJUSTO", new BigDecimal("50"), null);
                    justo.put("minOrderCents", UNIDAD_CENTIMOS * 2);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, justo).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTJUSTO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").isNull()).isTrue();
                    assertThat(r.cuerpo().get("discountCents").asInt()).isEqualTo(2800);
                }),

                paso("Un cupón NOMINATIVO de otra cuenta no se lo puede poner", () -> {
                    Map<String, Object> ajeno = cupon("CERTAJENO", new BigDecimal("50"), null);
                    ajeno.put("userId", idOtroCliente.toString());
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin, ajeno).status())
                            .isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTAJENO",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("couponError").asText())
                            .isEqualTo("Ese cupón no está disponible para tu cuenta");
                    assertThat(r.cuerpo().get("discountCents").asInt()).isZero();
                }),

                paso("El cupón NO se suma al descuento de referido: gana el mayor de los dos", () -> {
                    // Se devuelve la atribución de referido (10 % = 560) y se prueba un cupón del 5 %
                    // (280). Como 280 no supera a 560, el cupón se descarta entero y el descuento sigue
                    // siendo 560: nunca 840.
                    jdbcTemplate.update("UPDATE affiliate_attribution SET referred_user_id = ?"
                            + " WHERE visitor_token = ?", idCliente, tokenVisitante);
                    assertThat(llamar(HttpMethod.POST, "/api/admin/promotions", tokenAdmin,
                            cupon("CERTPEOR", new BigDecimal("5"), null)).status()).isEqualTo(200);
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "couponCode", "CERTPEOR",
                                    "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.cuerpo().get("discountCents").asInt())
                            .as("ni 840 (suma) ni 280 (cupón): manda el mejor de los dos").isEqualTo(560);
                    assertThat(r.cuerpo().get("couponError").asText())
                            .isEqualTo("Ya tienes un descuento mejor aplicado");
                    jdbcTemplate.update("UPDATE affiliate_attribution SET referred_user_id = NULL"
                            + " WHERE referred_user_id = ?", idCliente);
                }),

                paso("Un código de cupón más largo de lo permitido se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                                checkout(1, "WALLET", "X".repeat(41))).status()).isEqualTo(400)));
    }

    /* ── 9. Vista previa del checkout y pago con el monedero ────────────────────────────────────── */

    private List<DynamicTest> bloqueVistaPreviaYPago() {
        return List.of(
                paso("Guarda su dirección de envío", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/addresses", tokenCliente,
                            Map.of("fullName", "Ada Lovelace", "line1", "Calle Mayor 1", "city", "Madrid",
                                    "postalCode", "28001", "country", "ES", "phone", "+34600000001",
                                    "isDefault", true));
                    assertThat(r.status()).isEqualTo(201);
                    idDireccion = UUID.fromString(r.cuerpo().get("id").asText());
                }),

                paso("Una dirección sin ciudad se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/addresses", tokenCliente,
                                Map.of("fullName", "Ada", "line1", "Calle Mayor 1", "country", "ES"))
                                .status()).isEqualTo(400)),

                paso("La vista previa del checkout cuadra al céntimo: 56,00 + 10,99 + 13,54 = 80,53 $", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/shipping/quote", tokenCliente,
                            Map.of("country", "ES", "items", List.of(linea(idProducto, idVariante, 2))));
                    assertThat(r.status()).isEqualTo(200);
                    // Base imponible = (5.600 − 0) + 849 de porte SIN el despacho = 6.449.
                    // Impuesto = 6.449 × 2.100 / 10.000 = 1.354,29 → 1.354 al céntimo más cercano.
                    int baseImponible = UNIDAD_CENTIMOS * 2 + ENVIO_BASE_CENTIMOS + ENVIO_POR_KG_CENTIMOS;
                    assertThat(baseImponible).isEqualTo(6449);
                    int impuesto = Math.round(baseImponible * IVA_ES_BPS / 10000.0f);
                    assertThat(impuesto).isEqualTo(1354);
                    totalPedidoCentimos = (long) UNIDAD_CENTIMOS * 2
                            + ENVIO_BASE_CENTIMOS + ENVIO_POR_KG_CENTIMOS + DESPACHO_ES_CENTIMOS + impuesto;
                    assertThat(totalPedidoCentimos).isEqualTo(8053L);

                    assertThat(r.cuerpo().get("taxFormatted").asText()).isEqualTo("$13.54");
                    assertThat(r.cuerpo().get("shippingFormatted").asText()).isEqualTo("$10.99");
                    assertThat(r.cuerpo().get("totalFormatted").asText())
                            .as("lo que se enseña antes de pagar").isEqualTo("$80.53");
                }),

                paso("Sin monedero, pagar con saldo se rechaza y NO deja ningún pedido", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                            checkout(2, "WALLET"));
                    assertThat(r.status()).isEqualTo(404);
                    assertThat(enteroEnBd("SELECT count(*) FROM customer_order WHERE user_id = ?", idCliente))
                            .as("un pago fallido no puede dejar pedidos sueltos").isZero();
                }),

                paso("Con un céntimo MENOS del total, el pago se rechaza por saldo insuficiente", () -> {
                    // El borde exacto de la comparación: 8.052 no llega, 8.053 sí.
                    crearMonedero(totalPedidoCentimos - 1);
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                            checkout(2, "WALLET"));
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("WALLET_INSUFFICIENT_BALANCE");
                    assertThat(saldo()).as("el saldo no se toca en un pago rechazado")
                            .isEqualTo(totalPedidoCentimos - 1);
                    assertThat(enteroEnBd("SELECT count(*) FROM customer_order WHERE user_id = ?", idCliente))
                            .isZero();
                }),

                paso("Con el importe EXACTO del pedido, el pago sí entra y deja el saldo a cero", () -> {
                    jdbcTemplate.update("UPDATE wallet SET balance_usd_cents = ? WHERE user_id = ?",
                            totalPedidoCentimos, idCliente);
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                            checkout(2, "WALLET"));
                    assertThat(r.status()).isEqualTo(201);
                    assertThat(saldo()).as("pagar el importe justo deja el monedero a cero").isZero();
                    // Ese pedido era solo para probar el borde: se descarta devolviendo el importe.
                    llamar(HttpMethod.POST, "/api/me/orders/" + r.cuerpo().get("id").asText()
                            + "/cancel?refundToWallet=true", tokenCliente, null);
                    assertThat(saldo()).isEqualTo(totalPedidoCentimos);
                }),

                paso("Pedir CERO unidades se rechaza por validación", () -> {
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                            checkout(0, "WALLET"));
                    assertThat(r.status()).isEqualTo(400);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("VE001");
                }),

                paso("Pedir una unidad por encima del tope (100.001) se rechaza por validación", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                                checkout(100_001, "WALLET")).status()).isEqualTo(400)),

                paso("Un carrito SIN líneas se rechaza por validación", () -> {
                    Map<String, Object> cuerpo = new LinkedHashMap<>();
                    cuerpo.put("shippingAddressId", idDireccion.toString());
                    cuerpo.put("items", List.of());
                    cuerpo.put("paymentMethod", "WALLET");
                    assertThat(llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente, cuerpo).status())
                            .isEqualTo(400);
                }),

                paso("Comprar con la dirección de OTRO cliente responde 404, nunca 403", () -> {
                    // Un 403 confirmaría al atacante que esa dirección existe; el 404 no dice nada.
                    Respuesta ajena = llamar(HttpMethod.POST, "/api/me/addresses", tokenOtroCliente,
                            Map.of("fullName", "Otro", "line1", "Gran Via 2", "city", "Madrid",
                                    "postalCode", "28013", "country", "ES"));
                    Map<String, Object> cuerpo = new LinkedHashMap<>();
                    cuerpo.put("shippingAddressId", ajena.cuerpo().get("id").asText());
                    cuerpo.put("items", List.of(linea(idProducto, idVariante, 1)));
                    cuerpo.put("paymentMethod", "WALLET");
                    assertThat(llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente, cuerpo).status())
                            .isEqualTo(404);
                }),

                paso("Paga el pedido bueno CON el cupón: 73,76 $ EXACTOS", () -> {
                    jdbcTemplate.update("UPDATE wallet SET balance_usd_cents = ? WHERE user_id = ?",
                            SALDO_INICIAL, idCliente);
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                            checkout(2, "WALLET", "CERT10"), "cert-recorrido-001");
                    assertThat(r.status()).isEqualTo(201);
                    idPedido = UUID.fromString(r.cuerpo().get("id").asText());
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("PAID");
                    importeExacto("subtotal del pedido", r.cuerpo().get("subtotal"), "56.00");
                    importeExacto("descuento del cupón", r.cuerpo().get("discount"), "5.60");
                    importeExacto("envío del pedido", r.cuerpo().get("shipping"), "10.99");
                    importeExacto("impuesto del pedido", r.cuerpo().get("tax"), "12.37");
                    importeExacto("TOTAL del pedido", r.cuerpo().get("total"), "73.76");
                    assertThat(r.cuerpo().get("totalFormatted").asText())
                            .as("lo cobrado es EXACTAMENTE lo que enseñó la vista previa").isEqualTo("$73.76");
                }),

                paso("El monedero queda en 126,24 $: ni un céntimo de más ni de menos", () ->
                        // 20.000 − 7.376 = 12.624. Comprobado contra el saldo REAL, no contra la respuesta.
                        assertThat(saldo()).isEqualTo(SALDO_INICIAL - totalConCuponCentimos)),

                paso("El cupón queda CANJEADO: un uso contado y su apunte de canje", () -> {
                    assertThat(enteroEnBd("SELECT used_count FROM promotion WHERE code = ?", "CERT10"))
                            .isEqualTo(1);
                    Map<String, Object> canje = jdbcTemplate.queryForMap(
                            "SELECT r.user_id, r.order_id, r.amount_cents FROM promotion_redemption r"
                                    + " JOIN promotion p ON p.id = r.promotion_id WHERE p.code = ?", "CERT10");
                    assertThat(canje.get("user_id")).hasToString(idCliente.toString());
                    assertThat(canje.get("order_id")).hasToString(idPedido.toString());
                    assertThat(((Number) canje.get("amount_cents")).intValue())
                            .as("se anota el descuento realmente aplicado").isEqualTo(560);
                }),

                paso("El apunte del monedero registra el cargo exacto y su saldo posterior", () -> {
                    Map<String, Object> apunte = jdbcTemplate.queryForMap(
                            "SELECT t.kind, t.amount_usd_cents, t.balance_after_cents FROM wallet_transaction t"
                                    + " JOIN wallet w ON w.id = t.wallet_id WHERE w.user_id = ?"
                                    + " ORDER BY t.created_at DESC LIMIT 1", idCliente);
                    assertThat(apunte.get("kind")).isEqualTo("PAYMENT");
                    assertThat(((Number) apunte.get("amount_usd_cents")).longValue())
                            .isEqualTo(-totalConCuponCentimos);
                    assertThat(((Number) apunte.get("balance_after_cents")).longValue())
                            .isEqualTo(SALDO_INICIAL - totalConCuponCentimos);
                }),

                paso("Repetir el checkout con la MISMA clave devuelve el pedido original y no vuelve a cobrar",
                        () -> {
                            // Es la protección contra el doble clic y contra el reintento del navegador: la
                            // misma clave tiene que devolver EL MISMO pedido, sin crear otro ni tocar el saldo.
                            long antes = saldo();
                            Respuesta r = llamar(HttpMethod.POST, "/api/me/orders/checkout", tokenCliente,
                                    checkout(2, "WALLET", "CERT10"), "cert-recorrido-001");
                            assertThat(r.status()).isEqualTo(201);
                            assertThat(r.cuerpo().get("id").asText())
                                    .as("mismo pedido, no uno nuevo").isEqualTo(idPedido.toString());
                            assertThat(saldo()).as("no se cobra dos veces").isEqualTo(antes);
                            assertThat(enteroEnBd("SELECT count(*) FROM customer_order WHERE user_id = ?"
                                    + " AND status = 'PAID'", idCliente))
                                    .as("y no aparece un segundo pedido pagado").isEqualTo(1);
                            assertThat(enteroEnBd("SELECT used_count FROM promotion WHERE code = ?", "CERT10"))
                                    .as("ni el cupón se canjea dos veces").isEqualTo(1);
                        }));
    }

    /* ── 10. El pedido: consulta, factura y seguimiento ─────────────────────────────────────────── */

    private List<DynamicTest> bloquePedido() {
        return List.of(
                paso("Su lista de pedidos muestra el pedido recién pagado, y uno solo está PAGADO", () -> {
                    // En la lista conviven el pedido bueno y el que se usó para probar el borde del saldo,
                    // que quedó CANCELADO. Lo que se certifica es que hay exactamente UN pedido pagado y
                    // que sus importes son los del cobro.
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/orders", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    List<JsonNode> pagados = new ArrayList<>();
                    r.cuerpo().forEach(n -> {
                        if ("PAID".equals(n.get("status").asText())) {
                            pagados.add(n);
                        }
                    });
                    assertThat(pagados).hasSize(1);
                    JsonNode fila = pagados.get(0);
                    assertThat(fila.get("id").asText()).isEqualTo(idPedido.toString());
                    assertThat(fila.get("totalCents").asLong()).isEqualTo(totalConCuponCentimos);
                    assertThat(fila.get("totalFormatted").asText()).isEqualTo("$73.76");
                }),

                paso("El detalle del pedido conserva la dirección y la línea comprada", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/orders/" + idPedido + "?lang=es",
                            tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("shippingAddress").get("city").asText()).isEqualTo("Madrid");
                    assertThat(r.cuerpo().get("shippingAddress").get("country").asText()).isEqualTo("ES");
                    assertThat(r.cuerpo().get("items")).hasSize(1);
                    JsonNode linea = r.cuerpo().get("items").get(0);
                    assertThat(linea.get("quantity").asInt()).isEqualTo(2);
                    importeExacto("precio unitario congelado", linea.get("unitPrice"), "28.00");
                    importeExacto("total de la línea", linea.get("lineTotal"), "56.00");
                    assertThat(linea.get("productTitle").asText()).isEqualTo("Camiseta de certificación");
                }),

                paso("El pedido de otro cliente responde 404 (no se filtra su existencia)", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/me/orders/" + idPedido, tokenOtroCliente, null)
                                .status()).isEqualTo(404)),

                paso("Un pedido inexistente también responde 404", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/me/orders/" + UUID.randomUUID(), tokenCliente, null)
                                .status()).isEqualTo(404)),

                paso("Descarga su factura en PDF", () -> {
                    EntityExchangeResult<byte[]> r = client.get()
                            .uri("/api/me/orders/" + idPedido + "/invoice.pdf")
                            .header("X-Forwarded-For", IP_PROPIA)
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenCliente))
                            .exchange().expectBody().returnResult();
                    assertThat(r.getStatus().value()).isEqualTo(200);
                    assertThat(r.getResponseHeaders().getContentType().toString()).startsWith("application/pdf");
                    byte[] pdf = r.getResponseBody();
                    assertThat(pdf).isNotEmpty();
                    // Un PDF de verdad empieza por la cabecera %PDF: se comprueba el contenido, no el 200.
                    assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
                }),

                paso("La factura de otro cliente no se descarga", () ->
                        assertThat(client.get().uri("/api/me/orders/" + idPedido + "/invoice.pdf")
                                .header("X-Forwarded-For", IP_PROPIA)
                                .header(HttpHeaders.AUTHORIZATION, bearer(tokenOtroCliente))
                                .exchange().expectBody().returnResult().getStatus().value()).isEqualTo(404)),

                paso("Consulta el seguimiento del envío antes de que exista guía", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/orders/" + idPedido + "/tracking",
                            tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("PAID");
                    assertThat(r.cuerpo().get("trackingNumber").isNull())
                            .as("aún no se ha enviado: no puede haber número de seguimiento").isTrue();
                }),

                paso("El seguimiento del pedido de otro cliente responde 404", () ->
                        assertThat(llamar(HttpMethod.GET, "/api/me/orders/" + idPedido + "/tracking",
                                tokenOtroCliente, null).status()).isEqualTo(404)));
    }

    /* ── 11. Cancelación y devolución del saldo ─────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueCancelacionYReembolso() {
        return List.of(
                paso("Cancela el pedido y elige que le devuelvan el dinero al monedero", () -> {
                    Respuesta r = llamar(HttpMethod.POST,
                            "/api/me/orders/" + idPedido + "/cancel?refundToWallet=true", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("CANCELLED");
                    assertThat(r.cuerpo().get("cancelledAt").isNull()).isFalse();
                }),

                paso("El saldo vuelve EXACTAMENTE a los 200,00 $ de partida", () ->
                        // 11.947 + 8.053 = 20.000. La devolución es del total, ni un céntimo más.
                        assertThat(saldo()).isEqualTo(SALDO_INICIAL)),

                paso("El apunte de la devolución es por el importe exacto del pedido", () -> {
                    Map<String, Object> apunte = jdbcTemplate.queryForMap(
                            "SELECT t.kind, t.amount_usd_cents FROM wallet_transaction t"
                                    + " JOIN wallet w ON w.id = t.wallet_id WHERE w.user_id = ?"
                                    + " ORDER BY t.created_at DESC LIMIT 1", idCliente);
                    assertThat(apunte.get("kind")).isEqualTo("DEPOSIT");
                    assertThat(((Number) apunte.get("amount_usd_cents")).longValue())
                            .isEqualTo(totalConCuponCentimos);
                }),

                paso("Cancelar un pedido YA cancelado se rechaza y no devuelve dinero otra vez", () -> {
                    long antes = saldo();
                    Respuesta r = llamar(HttpMethod.POST,
                            "/api/me/orders/" + idPedido + "/cancel?refundToWallet=true", tokenCliente, null);
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("ORDER_NOT_CANCELLABLE");
                    assertThat(saldo()).as("un estado imposible no puede pagar dos veces").isEqualTo(antes);
                }),

                paso("Cancelar el pedido de otro cliente responde 404", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/me/orders/" + idPedido + "/cancel",
                                tokenOtroCliente, null).status()).isEqualTo(404)),

                paso("El pedido cancelado sigue siendo consultable y facturable", () -> {
                    Respuesta r = llamar(HttpMethod.GET, "/api/me/orders/" + idPedido + "?lang=es",
                            tokenCliente, null);
                    assertThat(r.status()).isEqualTo(200);
                    assertThat(r.cuerpo().get("status").asText()).isEqualTo("CANCELLED");
                    importeExacto("el importe cancelado no se reescribe", r.cuerpo().get("total"), "73.76");
                }));
    }

    /* ── 12. Baja de la cuenta ──────────────────────────────────────────────────────────────────── */

    private List<DynamicTest> bloqueBajaDeCuenta() {
        return List.of(
                paso("Pide la baja y recibe un código de confirmación de seis dígitos", () -> {
                    // 202: la baja se ACEPTA y queda pendiente de que el usuario confirme con el código.
                    assertThat(llamar(HttpMethod.POST, "/api/me/delete/request", tokenCliente, null).status())
                            .isEqualTo(202);
                    String codigo = textoEnBd("SELECT deletion_code FROM users WHERE id = ?", idCliente);
                    assertThat(codigo).hasSize(6).containsOnlyDigits();
                }),

                paso("Confirmar la baja con un código equivocado no da de baja la cuenta", () -> {
                    String bueno = textoEnBd("SELECT deletion_code FROM users WHERE id = ?", idCliente);
                    String malo = bueno.equals("000000") ? "111111" : "000000";
                    Respuesta r = llamar(HttpMethod.POST, "/api/me/delete/confirm", tokenCliente,
                            Map.of("code", malo));
                    assertThat(r.status()).isEqualTo(422);
                    assertThat(r.cuerpo().get("code").asText()).isEqualTo("DELETION_CODE_INVALID");
                    assertThat(booleanoEnBd("SELECT active FROM users WHERE id = ?", idCliente)).isTrue();
                }),

                paso("Con el código correcto la cuenta se da de baja SIN borrar la fila", () -> {
                    String codigo = textoEnBd("SELECT deletion_code FROM users WHERE id = ?", idCliente);
                    assertThat(llamar(HttpMethod.POST, "/api/me/delete/confirm", tokenCliente,
                            Map.of("code", codigo)).status()).isEqualTo(204);
                    // Borrado LÓGICO: la fila sigue ahí (el histórico de pedidos y facturas depende de ella).
                    assertThat(enteroEnBd("SELECT count(*) FROM users WHERE id = ?", idCliente)).isEqualTo(1);
                    assertThat(booleanoEnBd("SELECT active FROM users WHERE id = ?", idCliente)).isFalse();
                    assertThat(textoEnBd("SELECT deleted_at::text FROM users WHERE id = ?", idCliente))
                            .as("queda marcada la fecha de baja").isNotNull();
                    assertThat(textoEnBd("SELECT deletion_code FROM users WHERE id = ?", idCliente))
                            .as("el código se consume").isNull();
                }),

                paso("Tras la baja ya no puede volver a entrar con su contraseña", () ->
                        assertThat(llamar(HttpMethod.POST, "/api/auth/login", null,
                                Map.of("email", emailCliente, "password", CONTRASENA)).status()).isEqualTo(401)),

                paso("Tras la baja, su token queda REVOCADO: ya no sirve para nada", () -> {
                    // La baja cierra las sesiones abiertas; el token que tenía en la mano deja de valer,
                    // que es lo que impide seguir operando con una cuenta dada de baja.
                    assertThat(llamar(HttpMethod.POST, "/api/me/delete/confirm", tokenCliente,
                            Map.of("code", "000000")).status()).isEqualTo(401);
                    assertThat(llamar(HttpMethod.GET, "/api/me/orders", tokenCliente, null).status())
                            .isEqualTo(401);
                }));
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  CASOS QUE NO SE PUEDEN EJECUTAR EN ESTA APLICACIÓN (no se omiten: se declaran y se explican)
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    @Test
    @Disabled("No hay petición de reembolso por parte del cliente: el reembolso es una operación de"
            + " administración (POST /api/admin/orders/{id}/refund) y el contrato del cliente"
            + " (MeOrderApi) solo expone checkout, listado, detalle y cancelación. Lo autoservicio es la"
            + " cancelación con devolución, certificada en el recorrido; el reembolso del administrador se"
            + " certifica en AdminJourneyIT.")
    @DisplayName("El cliente solicita el reembolso de un pedido ya enviado")
    void solicitudDeReembolsoPorElCliente() {
        // Sin implementación que ejercitar.
    }

    @Test
    @Disabled("El pago con tarjeta/PayPal/USDT no se puede recorrer de extremo a extremo aquí: deja el"
            + " pedido en PENDING y exige completar el cobro en la pasarela externa"
            + " (/api/me/orders/{id}/payment-intent), que en la certificación está apagada"
            + " (nexadrop.stripe.enabled=false y sin credenciales). El pago con monedero, que es el que"
            + " mueve dinero real de la plataforma, sí queda certificado al céntimo.")
    @DisplayName("Pagar el pedido con tarjeta y volver de la pasarela")
    void pagoConPasarelaExterna() {
        // Sin implementación que ejercitar.
    }

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  PREPARACIÓN DEL ESCENARIO (la tienda ya existe cuando el cliente llega)
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    /**
     * Deja la tienda montada con números elegidos para que la aritmética sea comprobable a mano.
     *
     * <p>{@code BaseIntegration} vacía TODAS las tablas antes de cada test, incluidas las que siembran
     * las migraciones (divisas, zonas de envío, impuestos). Por eso aquí se siembra todo explícitamente:
     * lo que no se siembre, no existe. Las cachés en memoria (Caffeine del catálogo, la de divisas y la
     * de reglas de margen) sobreviven al vaciado, así que se vacían o se refrescan por la vía del
     * administrador, que es la que las invalida de verdad.
     */
    private void prepararTienda() {
        tokenAdmin = jwt.userToken("ADMIN");
        vaciarCaches();

        // Divisas. La tasa es "unidades por dólar": 8 CNY = 1 USD hace que 80 CNY sean 10 USD exactos.
        insertarDivisa("USD", "Dólar", "$", "en-US", "1.00000000");
        insertarDivisa("CNY", "Yuan", "¥", "zh-CN", "8.00000000");
        // El PUT del administrador es lo que refresca la caché de divisas del servicio de conversión.
        assertThat(llamar(HttpMethod.PUT, "/api/admin/currency/USD", tokenAdmin,
                Map.of("rateVsUsd", 1, "active", true)).status()).isEqualTo(200);

        // Cobertura de envío: España y Estados Unidos abiertos, Japón cubierto pero DESACTIVADO.
        insertarZona("ES", "España", "EU", ENVIO_BASE_CENTIMOS, ENVIO_POR_KG_CENTIMOS, 8, 18, true);
        insertarZona("US", "Estados Unidos", "AM", 799, 450, 10, 20, true);
        insertarZona("JP", "Japón", "AS", 999, 500, 12, 22, false);

        // Impuestos: 21 % nacional en España, 4 % nacional en Estados Unidos y 7,25 % en California.
        insertarImpuesto("ES", "IVA", IVA_ES_BPS);
        insertarImpuesto("US", "Sales tax", 400);
        jdbcTemplate.update("INSERT INTO country_region (id, country_code, region_code, region_name, rate_bps,"
                + " active, position, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), 'US', 'CA', 'California', ?, true, 0, now(), now())",
                IMPUESTO_CA_BPS);

        // Regla aduanera de España: 2,50 $ fijos de despacho DDP y umbral SIN configurar (0 = no evaluar).
        assertThat(llamar(HttpMethod.PUT, "/api/admin/customs-rules/ES", tokenAdmin,
                Map.of("taxMode", "DDP", "deMinimisAmount", 0, "deMinimisCurrency", "EUR",
                        "overThresholdPolicy", "SURCHARGE", "handlingFeeCents", DESPACHO_ES_CENTIMOS,
                        "handlingPercentBps", 0, "overThresholdSurchargeCents", 0, "dutyRateBps", 0,
                        "active", true)).status()).isEqualTo(200);

        // Margen global del 150 %. Se crea por la API del administrador porque es la vía que invalida la
        // caché de reglas del servicio de márgenes; un INSERT directo la dejaría con la foto anterior.
        // Ajuste de MOQ en su valor de fábrica (activo, a la mitad). Es un estado CACHEADO en memoria que
        // sobrevive al vaciado de tablas, así que se fija explícitamente para que el precio no dependa de
        // lo que dejara otra clase de la misma ejecución. El producto del recorrido tiene MOQ 1 —no le
        // afecta—; la rebaja por MOQ se certifica al céntimo en AdminJourneyIT.
        assertThat(llamar(HttpMethod.PUT, "/api/admin/pricing/moq-rule", tokenAdmin,
                Map.of("enabled", true, "factorPercent", 50)).status()).isEqualTo(200);

        assertThat(llamar(HttpMethod.POST, "/api/admin/pricing/rules", tokenAdmin,
                Map.of("scope", "GLOBAL", "marginType", "PERCENTAGE", "marginValue", 150, "active", true,
                        "position", 0, "description", "Certificación: margen global del escaparate"))
                .status()).isEqualTo(201);

        // Categoría y producto.
        Respuesta categoria = llamar(HttpMethod.POST, "/api/admin/catalog/categories", tokenAdmin,
                Map.of("slug", SLUG_CATEGORIA, "nameZh", "认证", "position", 0,
                        "nameTranslations", Map.of("es", "Certificación", "en", "Certification")));
        assertThat(categoria.status()).isEqualTo(200);

        Map<String, Object> producto = new LinkedHashMap<>();
        producto.put("categorySlug", SLUG_CATEGORIA);
        // La importación exige proveedor: con el nombre lo crea al vuelo (y lo reutiliza si ya existe).
        producto.put("supplierName", "Fábrica de certificación");
        producto.put("titleEs", "Camiseta de certificación");
        producto.put("titleEn", "Certification t-shirt");
        producto.put("descriptionEs", "Camiseta de algodón usada por la certificación de extremo a extremo.");
        producto.put("price", 80.0);          // CNY
        producto.put("shippingCny", 16.0);    // 2,00 USD, se suman SIN margen
        producto.put("ivaCny", 8.0);          // 1,00 USD, se suman SIN margen
        producto.put("moq", 1);
        producto.put("packageWeightGrams", 500);
        producto.put("imageUrls", List.of("https://cert.local/camiseta.jpg"));
        producto.put("variantAxes", List.of(Map.of("name", "Color", "values", List.of("Rojo"))));
        producto.put("variants", List.of(Map.of("sku", "CERT-ROJO", "optionValues", Map.of("Color", "Rojo"),
                "price", 80.0, "stock", 25, "packageWeightGrams", 500)));
        Respuesta creado = llamar(HttpMethod.POST, "/api/admin/catalog/products/create", tokenAdmin, producto);
        assertThat(creado.status()).as("alta del producto de certificación").isEqualTo(200);
        idProducto = UUID.fromString(creado.cuerpo().asText());

        // El espejado de imágenes sube el fichero a S3/MinIO, que no existe en la certificación. El
        // escaparate solo lista productos con una imagen ya espejada (cdn_url no nulo), así que se
        // marca a mano: es preparación del escenario, no una comprobación.
        jdbcTemplate.update("UPDATE product_image SET cdn_url = source_url WHERE cdn_url IS NULL");

        Respuesta detalle = llamar(HttpMethod.GET, "/api/admin/catalog/products/" + idProducto + "?lang=es",
                tokenAdmin, null);
        assertThat(detalle.status()).isEqualTo(200);
        slugProducto = detalle.cuerpo().get("slug").asText();

        // Un segundo cliente, para los cruces de autorización. Se crea directamente en base de datos: el
        // alta pública está limitada a cinco por hora y por IP, y esa cuota es para el recorrido real.
        idOtroCliente = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, active, language, display_name,"
                + " failed_login_count, created_at, updated_at)"
                + " VALUES (?, ?, '$2a$10$notusedbecauseweminttokens0000000000000000000000000000', 'USER',"
                + " true, 'es', 'Otro Cliente', 0, now(), now())",
                idOtroCliente, "cert-otro-" + idOtroCliente + "@example.com");
        tokenOtroCliente = jwt.userToken(idOtroCliente, "cert-otro-" + idOtroCliente + "@example.com", "USER");

        vaciarCaches();
    }

    private void insertarDivisa(String codigo, String nombre, String simbolo, String locale, String tasa) {
        jdbcTemplate.update("INSERT INTO currency_rate (id, code, name, symbol, locale, rate_vs_usd, active,"
                + " created_at, updated_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::numeric, true, now(), now())",
                codigo, nombre, simbolo, locale, tasa);
    }

    private void insertarZona(String pais, String nombre, String zona, int base, int porKg, int etaMin,
            int etaMax, boolean activa) {
        jdbcTemplate.update("INSERT INTO cainiao_shipping_zone (id, country_code, country_name, zone, base_cents,"
                + " per_kg_cents, eta_min_days, eta_max_days, enabled, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                pais, nombre, zona, base, porKg, etaMin, etaMax, activa);
    }

    private void insertarImpuesto(String pais, String etiqueta, int bps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active, created_at,"
                + " updated_at) VALUES (gen_random_uuid(), ?, ?, ?, true, now(), now())", pais, etiqueta, bps);
    }

    private void crearMonedero(long saldoCentimos) {
        jdbcTemplate.update("INSERT INTO wallet (id, user_id, balance_usd_cents, hold_usd_cents, currency_default,"
                + " status, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, ?, 0, 'USD', 'ACTIVE', now(), now())", idCliente, saldoCentimos);
    }

    private long saldo() {
        return jdbcTemplate.queryForObject("SELECT balance_usd_cents FROM wallet WHERE user_id = ?", Long.class,
                idCliente);
    }

    // vaciarCaches() vive ahora en BaseIntegration: lo necesitaba TODA la suite, no solo estas clases.
    // Vaciar la base sin vaciar las cachés dejaba pasar estado de una clase de prueba a la siguiente.

    /* ══════════════════════════════════════════════════════════════════════════════════════════════
     *  UTILIDADES
     * ══════════════════════════════════════════════════════════════════════════════════════════════ */

    /** Status + cuerpo ya interpretado, que es lo único que necesitan los pasos. */
    private record Respuesta(int status, JsonNode cuerpo) {
    }

    /**
     * Envuelve un paso del recorrido. Si un paso anterior falló, los siguientes se OMITEN en vez de
     * arrastrar fallos derivados: el informe señala así el punto exacto donde se rompió la cadena.
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
        return llamar(metodo, uri, token, cuerpo, null);
    }

    /** Igual que {@link #llamar}, pero declarando otra IP de origen (cubos del limitador separados). */
    private Respuesta llamarDesde(String ip, HttpMethod metodo, String uri, String token, Object cuerpo) {
        WebTestClient.RequestBodySpec peticion = client.method(metodo).uri(uri).header("X-Forwarded-For", ip);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        WebTestClient.ResponseSpec respuesta = cuerpo == null
                ? peticion.exchange()
                : peticion.contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange();
        EntityExchangeResult<byte[]> resultado = respuesta.expectBody().returnResult();
        return new Respuesta(resultado.getStatus().value(), interpretar(resultado.getResponseBody()));
    }

    private Respuesta llamar(HttpMethod metodo, String uri, String token, Object cuerpo, String claveIdempotencia) {
        WebTestClient.RequestBodySpec peticion = client.method(metodo).uri(uri).header("X-Forwarded-For", IP_PROPIA);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        if (claveIdempotencia != null) {
            peticion.header("Idempotency-Key", claveIdempotencia);
        }
        WebTestClient.ResponseSpec respuesta = cuerpo == null
                ? peticion.exchange()
                : peticion.contentType(MediaType.APPLICATION_JSON).bodyValue(cuerpo).exchange();
        EntityExchangeResult<byte[]> resultado = respuesta.expectBody().returnResult();
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

    /**
     * Comprueba un importe AL CÉNTIMO. Se compara el valor numérico con {@code compareTo} para que la
     * escala no enmascare una diferencia real (80.5 y 80.50 son el mismo dinero; 80.53 y 80.54 no).
     */
    private static void importeExacto(String concepto, JsonNode nodo, String esperado) {
        assertThat(nodo).as("%s: el importe no viene en la respuesta", concepto).isNotNull();
        assertThat(nodo.isNull()).as("%s: el importe llega nulo", concepto).isFalse();
        assertThat(new BigDecimal(nodo.asText())).as("%s", concepto)
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal(esperado));
    }

    private static Map<String, Object> linea(UUID producto, UUID variante, int cantidad) {
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("productId", producto.toString());
        linea.put("variantId", variante == null ? null : variante.toString());
        linea.put("quantity", cantidad);
        return linea;
    }

    private Map<String, Object> checkout(int cantidad, String metodoPago) {
        return checkout(cantidad, metodoPago, null);
    }

    private Map<String, Object> checkout(int cantidad, String metodoPago, String cupon) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("shippingAddressId", idDireccion == null ? null : idDireccion.toString());
        cuerpo.put("items", List.of(linea(idProducto, idVariante, cantidad)));
        cuerpo.put("paymentMethod", metodoPago);
        cuerpo.put("couponCode", cupon);
        return cuerpo;
    }

    /** Un cupón de administración: porcentaje O importe fijo, nunca los dos (lo rechaza el servidor). */
    private static Map<String, Object> cupon(String codigo, BigDecimal porcentaje, Integer importeCentimos) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("name", "Certificación " + codigo);
        cuerpo.put("code", codigo);
        cuerpo.put("kind", "COUPON");
        cuerpo.put("scope", "ALL");
        cuerpo.put("percentOff", porcentaje);
        cuerpo.put("amountOffCents", importeCentimos);
        cuerpo.put("active", true);
        return cuerpo;
    }

    /** Una línea de la lista de guardados, con el precio del proveedor tal y como lo manda el front. */
    private Map<String, Object> lineaGuardada(int cantidad) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("productId", idProducto.toString());
        cuerpo.put("variantId", idVariante.toString());
        cuerpo.put("slug", slugProducto);
        cuerpo.put("title", "Camiseta de certificación");
        cuerpo.put("unitPriceSource", 80.0);
        cuerpo.put("sourceCurrency", "CNY");
        cuerpo.put("quantity", cantidad);
        return cuerpo;
    }

    private String textoEnBd(String sql, Object parametro) {
        return jdbcTemplate.queryForObject(sql, String.class, parametro);
    }

    private boolean booleanoEnBd(String sql, Object parametro) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, parametro));
    }

    private int enteroEnBd(String sql, Object parametro) {
        Integer valor = jdbcTemplate.queryForObject(sql, Integer.class, parametro);
        return valor == null ? 0 : valor;
    }

    /* ── Generador de códigos de un solo uso (RFC 6238), para poder ejercitar el doble factor ───── */

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /**
     * Reproduce el mismo cálculo que la aplicación (HMAC-SHA1, seis dígitos, ventana de 30 s) para poder
     * presentar un código válido. Sin esto el alta del doble factor no se podría certificar de verdad.
     */
    private static String totp(String secretoBase32, long segundos) {
        try {
            long contador = segundos / 30;
            byte[] datos = new byte[8];
            for (int i = 7; i >= 0; i--) {
                datos[i] = (byte) (contador & 0xFF);
                contador >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32(secretoBase32), "HmacSHA1"));
            byte[] hash = mac.doFinal(datos);
            int desplazamiento = hash[hash.length - 1] & 0x0F;
            int codigo = ((hash[desplazamiento] & 0x7F) << 24) | ((hash[desplazamiento + 1] & 0xFF) << 16)
                    | ((hash[desplazamiento + 2] & 0xFF) << 8) | (hash[desplazamiento + 3] & 0xFF);
            return String.format("%06d", codigo % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo generar el código de un solo uso", e);
        }
    }

    private static byte[] base32(String texto) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        int bits = 0;
        int valor = 0;
        for (char c : texto.toUpperCase().toCharArray()) {
            int indice = BASE32.indexOf(c);
            if (indice < 0) {
                continue;
            }
            valor = (valor << 5) | indice;
            bits += 5;
            if (bits >= 8) {
                salida.write((valor >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return salida.toByteArray();
    }
}
