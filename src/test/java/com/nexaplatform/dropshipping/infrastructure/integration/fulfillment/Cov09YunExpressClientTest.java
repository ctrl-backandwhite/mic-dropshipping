package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transporte del cliente de YunExpress contra un servidor HTTP local (sin salir a internet): cacheo del
 * token, firma de cada llamada y qué respuestas se consideran error.
 *
 * <p>Las dos reglas caras de estos tests: (1) un {@code 400} con {@code success:false} es una respuesta de
 * NEGOCIO y se devuelve tal cual —tratarla como caída haría que un "la guía todavía no existe" tumbase el
 * sondeo de tracking—; y (2) un {@code 401} se reintenta UNA vez con token nuevo, porque el token dura 2 h
 * y puede caducar en el reloj del gateway antes que en el nuestro.
 */
class Cov09YunExpressClientTest {

    private static final String SECRETO = "app-secret-de-prueba";
    private static final String RUTA_TOKEN = "/openapi/oauth2/token";
    private static final String RUTA_NEGOCIO = "/v1/eco";

    private HttpServer server;
    private YunExpressClient client;

    /** Peticiones recibidas por el endpoint de negocio, en orden. */
    private final List<Peticion> recibidas = new CopyOnWriteArrayList<>();
    /** Respuestas que dará el endpoint de negocio, una por llamada (la última se repite). */
    private final Deque<Respuesta> guion = new ConcurrentLinkedDeque<>();
    private final AtomicInteger tokensPedidos = new AtomicInteger();
    private final Deque<Respuesta> guionToken = new ConcurrentLinkedDeque<>();

    private record Respuesta(int status, String body) {
    }

    private record Peticion(String metodo, String uri, Map<String, String> cabeceras, String cuerpo) {
    }

    @BeforeEach
    void arrancarServidorLocal() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            Assumptions.abort("No se pudo abrir un puerto local para el servidor de prueba: " + e.getMessage());
            return;
        }
        server.createContext(RUTA_TOKEN, ex -> {
            tokensPedidos.incrementAndGet();
            registrar(ex);
            Respuesta r = siguiente(guionToken,
                    new Respuesta(200, "{\"accessToken\":\"tok-" + tokensPedidos.get() + "\",\"expiresIn\":7200}"));
            responder(ex, r);
        });
        server.createContext(RUTA_NEGOCIO, ex -> {
            registrar(ex);
            responder(ex, siguiente(guion, new Respuesta(200, "{\"success\":true}")));
        });
        server.start();

        client = new YunExpressClient();
        set("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        set("appId", "app-1");
        set("appSecret", SECRETO);
        set("sourceKey", "src-1");
    }

    @AfterEach
    void pararServidorLocal() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void registrar(HttpExchange ex) throws IOException {
        Map<String, String> cabeceras = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> cabeceras.put(k.toLowerCase(), v.get(0)));
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        recibidas.add(new Peticion(ex.getRequestMethod(), ex.getRequestURI().toString(), cabeceras, cuerpo));
    }

    private static Respuesta siguiente(Deque<Respuesta> cola, Respuesta porDefecto) {
        return cola.isEmpty() ? porDefecto : (cola.size() == 1 ? cola.peek() : cola.poll());
    }

    private static void responder(HttpExchange ex, Respuesta r) throws IOException {
        byte[] salida = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), salida.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(salida);
        }
    }

    private void set(String campo, String valor) {
        try {
            Field f = YunExpressClient.class.getDeclaredField(campo);
            f.setAccessible(true);
            f.set(client, valor);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Peticion ultimaDeNegocio() {
        return recibidas.stream().filter(p -> p.uri().startsWith(RUTA_NEGOCIO)).reduce((a, b) -> b).orElseThrow();
    }

    // ---------------------------------------------------------------- credenciales

    @Test
    void sinAlgunaDeLasTresCredencialesElClienteSeDaPorNoConfigurado() {
        // hasCredentials es lo que decide si se llama a YunExpress o se usa el cálculo local: si diera true
        // con media credencial, cada envío se iría a un 401 en vez de caer al fallback.
        assertThat(client.hasCredentials()).isTrue();

        set("sourceKey", "  ");
        assertThat(client.hasCredentials()).isFalse();
        set("sourceKey", "src-1");
        set("appSecret", null);
        assertThat(client.hasCredentials()).isFalse();
        set("appSecret", SECRETO);
        set("appId", "");
        assertThat(client.hasCredentials()).isFalse();
    }

    // ---------------------------------------------------------------- token

    @Test
    void elTokenSeCacheaYNoSePideUnoNuevoEnCadaLlamada() {
        client.get(RUTA_NEGOCIO, Map.of());
        client.get(RUTA_NEGOCIO, Map.of());

        assertThat(tokensPedidos.get()).isEqualTo(1);
    }

    @Test
    void laPeticionDelTokenNoVaFirmadaPorqueTodaviaNoHayConQueAutenticarse() {
        client.get(RUTA_NEGOCIO, Map.of());

        Peticion token = recibidas.stream().filter(p -> p.uri().startsWith(RUTA_TOKEN)).findFirst().orElseThrow();
        assertThat(token.cabeceras()).doesNotContainKey("sign").doesNotContainKey("token");
        assertThat(token.cuerpo()).contains("client_credentials").contains("app-1").contains("src-1");
    }

    @Test
    void unaRespuestaDeTokenSinAccessTokenNoSeDaPorBuena() {
        // Seguir adelante con un token vacío convierte un problema de credenciales en un 401 indescifrable.
        guionToken.add(new Respuesta(200, "{\"success\":false,\"msg\":\"credenciales inválidas\"}"));

        assertThatThrownBy(() -> client.get(RUTA_NEGOCIO, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no trae accessToken");
    }

    @Test
    void invalidarElTokenObligaAPedirUnoNuevoEnLaSiguienteLlamada() {
        client.get(RUTA_NEGOCIO, Map.of());
        client.invalidateToken();
        client.get(RUTA_NEGOCIO, Map.of());

        assertThat(tokensPedidos.get()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- firma de cada llamada

    @Test
    void cadaLlamadaViajaConTokenFechaYFirmaDelPathSinQueryString() {
        // El gateway firma el path SIN query: incluirla hace que rechace todas las peticiones con filtros.
        client.get(RUTA_NEGOCIO, Map.of("trackingNumber", "YT123", "vacio", ""));

        Peticion p = ultimaDeNegocio();
        assertThat(p.uri()).isEqualTo(RUTA_NEGOCIO + "?trackingNumber=YT123");
        assertThat(p.cabeceras()).containsEntry("token", "tok-1");
        String date = p.cabeceras().get("date");
        assertThat(Long.parseLong(date)).isPositive();
        assertThat(p.cabeceras()).containsEntry("sign",
                YunExpressClient.sign(YunExpressClient.signatureContent("GET", RUTA_NEGOCIO, null, date), SECRETO));
    }

    @Test
    void elCuerpoDelPostViajaSerializadoYEntraEnLaFirma() {
        client.post(RUTA_NEGOCIO, Map.of("weight", 1200));

        Peticion p = ultimaDeNegocio();
        assertThat(p.metodo()).isEqualTo("POST");
        assertThat(p.cuerpo()).isEqualTo("{\"weight\":1200}");
        assertThat(p.cabeceras()).containsEntry("sign",
                YunExpressClient.sign(
                        YunExpressClient.signatureContent("POST", RUTA_NEGOCIO, p.cuerpo(), p.cabeceras().get("date")),
                        SECRETO));
    }

    // ---------------------------------------------------------------- errores y reintento

    @Test
    void unTokenRechazadoSeRenuevaYLaLlamadaSeReintentaUnaSolaVez() {
        guion.add(new Respuesta(401, "{\"msg\":\"token expired\"}"));
        guion.add(new Respuesta(200, "{\"success\":true,\"data\":42}"));

        JsonNode res = client.get(RUTA_NEGOCIO, Map.of());

        assertThat(res.path("data").asInt()).isEqualTo(42);
        assertThat(tokensPedidos.get()).isEqualTo(2); // el token se pidió de nuevo tras el 401
    }

    @Test
    void siElSegundoIntentoTambienEsRechazadoElErrorSePropaga() {
        // Reintentar en bucle contra un gateway que nos ha revocado las credenciales solo agrava el problema.
        guion.add(new Respuesta(401, "{\"msg\":\"token expired\"}"));

        assertThatThrownBy(() -> client.get(RUTA_NEGOCIO, Map.of()))
                .isInstanceOf(YunExpressClient.YunExpressAuthException.class).hasMessageContaining("401");
    }

    @Test
    void unErrorDeNegocioLlegaAlLlamanteAunqueVengaConHttpCuatrocientos() {
        // "La guía aún no se ha propagado" es un 400 con success:false; tratarlo como caída rompe el sondeo.
        guion.add(new Respuesta(400, "{\"success\":false,\"code\":\"1001\",\"msg\":\"no existe todavía\"}"));

        JsonNode res = client.get(RUTA_NEGOCIO, Map.of());

        assertThat(res.path("success").asBoolean()).isFalse();
        assertThat(res.path("code").asText()).isEqualTo("1001");
    }

    @Test
    void unaCaidaDelGatewayConCuerpoNoInterpretableEsUnError() {
        guion.add(new Respuesta(500, "<html>Bad Gateway</html>"));

        assertThatThrownBy(() -> client.get(RUTA_NEGOCIO, Map.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("YunExpress HTTP 500");
    }

    @Test
    void unaRespuestaCorrectaQueNoEsJsonEsUnError() {
        guion.add(new Respuesta(200, "no soy json"));

        assertThatThrownBy(() -> client.post(RUTA_NEGOCIO, "{}")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no es JSON válido");
    }
}
