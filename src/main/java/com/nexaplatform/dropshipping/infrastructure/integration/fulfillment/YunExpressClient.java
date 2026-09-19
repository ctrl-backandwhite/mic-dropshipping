package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cliente HTTP de bajo nivel para la <b>YunExpress Open Platform</b> (云途, open.yunexpress.cn).
 *
 * <p>Aquí vive solo la fontanería del transporte: obtener y cachear el {@code accessToken} de OAuth2,
 * firmar cada petición y ejecutar el GET/POST. El mapeo de negocio (crear envío, tracking, tarifa) vive
 * en {@link YunExpressFulfillmentService}.
 *
 * <p><b>Autenticación (OAuth2 client_credentials).</b> {@code POST /openapi/oauth2/token} con
 * {@code appId + appSecret + sourceKey} devuelve un {@code accessToken} válido {@code expiresIn}
 * segundos (2 h). Se cachea en memoria y se renueva con un margen de seguridad; el token viaja en la
 * cabecera {@code token} de cada llamada.
 *
 * <p><b>Firma.</b> Cada petición lleva además {@code date} (epoch en milisegundos, con una tolerancia de
 * 300 s en el gateway) y {@code sign}. El contenido firmado son los campos en orden alfabético unidos por
 * {@code &} — {@code body=..&date=..&method=..&uri=..}, omitiendo {@code body=} cuando no hay cuerpo — y
 * la firma es {@code Base64(HmacSHA256(contenido, appSecret))}. Coincide con la pantalla
 * <i>签名验证</i> del console y está cubierto por {@code YunExpressSignatureTest}.
 *
 * <p><b>Sandbox vs producción.</b> Solo cambia {@code nexadrop.yunexpress.base-url}
 * ({@code openapi-sbx.yunexpress.cn} → {@code openapi.yunexpress.cn}) y las credenciales; el resto del
 * contrato es idéntico.
 */
@Slf4j
@Component
public class YunExpressClient {

    /** Margen con el que se renueva el token antes de caducar, para que no expire en pleno vuelo. */
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);
    /** Espera por defecto de una llamada (crear envío, tracking, etiqueta): operaciones no interactivas. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Ruta del endpoint de token, parte del CONTRATO de la Open Platform y no un parámetro de despliegue:
     * es idéntica en sandbox y en producción (lo único que cambia entre entornos es el host, que sí es
     * configurable en {@code nexadrop.yunexpress.base-url}) y además entra en el contenido que se firma,
     * así que moverla por configuración invalidaría la firma en vez de apuntar a otro sitio.
     */
    private static final String TOKEN_PATH = "/openapi/oauth2/token"; // NOSONAR java:S1075 — ruta fija del contrato

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.yunexpress.base-url:https://openapi-sbx.yunexpress.cn}")
    private String baseUrl;
    /** Identificador de la aplicación (应用ID) del console. */
    @Value("${nexadrop.yunexpress.app-id:}")
    private String appId;
    /** Clave secreta de la aplicación (应用秘钥/AK). Solo por variable de entorno; nunca se loguea. */
    @Value("${nexadrop.yunexpress.app-secret:}")
    private String appSecret;
    /** Clave de origen del usuario (sourcekey), en 用户中心 → 用户信息 del console. */
    @Value("${nexadrop.yunexpress.source-key:}")
    private String sourceKey;

    /** Token cacheado y su instante de caducidad; se protegen con el monitor de la instancia. */
    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public boolean hasCredentials() {
        return notBlank(appId) && notBlank(appSecret) && notBlank(sourceKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ── Firma ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Contenido a firmar: los campos en orden alfabético unidos por {@code &}. El {@code uri} es el path
     * SIN query string, que es como lo espera el gateway.
     */
    static String signatureContent(String method, String uri, String body, String date) {
        StringBuilder sb = new StringBuilder();
        if (body != null && !body.isEmpty()) {
            sb.append("body=").append(body).append('&');
        }
        sb.append("date=").append(date)
                .append("&method=").append(method)
                .append("&uri=").append(uri);
        return sb.toString();
    }

    /** Firma un contenido con el {@code appSecret}: {@code Base64(HmacSHA256(contenido, secreto))}. */
    public String sign(String content) {
        return sign(content, appSecret);
    }

    /** Variante con secreto explícito, para poder verificar los vectores del console en los tests. */
    static String sign(String content, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("YunExpress: no se pudo calcular la firma HmacSHA256", e);
        }
    }

    // ── Token ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve un {@code accessToken} vivo, pidiendo uno nuevo solo si el cacheado va a caducar. La
     * petición de token es la única que NO va firmada (todavía no hay token con el que autenticarse).
     */
    synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedToken;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("grantType", "client_credentials");
        payload.put("appId", appId);
        payload.put("appSecret", appSecret);
        payload.put("sourceKey", sourceKey);
        String body = writeJson(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + TOKEN_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode response = readJson(send(request, TOKEN_PATH));
        String token = response.path("accessToken").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("YunExpress: la respuesta del token no trae accessToken: " + response);
        }
        long expiresIn = response.path("expiresIn").asLong(7200L);
        cachedToken = token;
        tokenExpiresAt = Instant.now().plusSeconds(expiresIn).minus(TOKEN_REFRESH_MARGIN);
        log.info("YunExpress: accessToken renovado (validez {} s)", expiresIn);
        return cachedToken;
    }

    /** Fuerza la renovación del token en la siguiente llamada (p.ej. tras un 401 del gateway). */
    synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    // ── Llamadas ─────────────────────────────────────────────────────────────────────────────────

    /** GET firmado. Los parámetros se codifican como query string; la firma usa el path sin query. */
    public JsonNode get(String path, Map<String, String> query) {
        return get(path, query, DEFAULT_TIMEOUT);
    }

    /**
     * GET firmado con un tiempo de espera propio. Existe para las llamadas del camino crítico —la
     * cotización del checkout—, donde esperar los 30 s por defecto dejaría al cliente mirando un spinner
     * cuando el fallback local puede responder al instante.
     */
    public JsonNode get(String path, Map<String, String> query, Duration timeout) {
        return execute("GET", path, baseUrl + path + encodeQuery(query), null, timeout);
    }

    /** POST firmado con cuerpo JSON ya serializado. */
    public JsonNode post(String path, String jsonBody) {
        return execute("POST", path, baseUrl + path, jsonBody, DEFAULT_TIMEOUT);
    }

    /** POST firmado serializando el objeto de negocio a JSON. */
    public JsonNode post(String path, Object payload) {
        return post(path, writeJson(payload));
    }

    /**
     * Ejecuta la llamada firmada. Un 401 del gateway se reintenta UNA vez con token nuevo: el token dura
     * 2 h y puede caducar por el reloj del servidor aunque el nuestro aún lo dé por válido.
     */
    private JsonNode execute(String method, String path, String url, String body, Duration timeout) {
        try {
            return readJson(send(signedRequest(method, path, url, body, timeout), path));
        } catch (YunExpressAuthException e) {
            log.warn("YunExpress: token rechazado en {}, renovando y reintentando", path);
            invalidateToken();
            return readJson(send(signedRequest(method, path, url, body, timeout), path));
        }
    }

    private HttpRequest signedRequest(String method, String path, String url, String body, Duration timeout) {
        String date = Long.toString(System.currentTimeMillis());
        String signature = sign(signatureContent(method, path, body, date));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Accept-Language", "en-US")
                .header("token", accessToken())
                .header("date", date)
                .header("sign", signature);
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(
                body == null ? "" : body, StandardCharsets.UTF_8)).build();
    }

    /**
     * Ejecuta la petición y devuelve el cuerpo.
     *
     * <p>YunExpress responde a los errores de <b>negocio</b> con HTTP 400 y un cuerpo
     * {@code {"success":false,"code":...,"msg":...}} — por ejemplo mientras una guía recién creada aún no
     * se ha propagado al servicio de trazabilidad. Ese cuerpo se devuelve tal cual para que cada operación
     * decida qué hacer: tratarlo como fallo de red haría que un "todavía no existe" tumbase el sondeo.
     * Solo se lanza excepción cuando no hay respuesta interpretable (5xx, cuerpo no JSON) o el token
     * caduca.
     */
    private String send(HttpRequest request, String path) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401) {
                throw new YunExpressAuthException("YunExpress 401 en " + path + ": " + response.body());
            }
            if (response.statusCode() / 100 != 2 && !isBusinessError(response.body())) {
                throw new IllegalStateException("YunExpress HTTP " + response.statusCode() + " en " + path
                        + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("YunExpress: fallo de red llamando a " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("YunExpress: llamada interrumpida en " + path, e);
        }
    }

    /** ¿El cuerpo es una respuesta de negocio de la API ({@code success:false}) y no un error del gateway? */
    static boolean isBusinessError(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node.has("success") && !node.path("success").asBoolean(true);
        } catch (IOException e) {
            return false;
        }
    }

    private JsonNode readJson(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("YunExpress: respuesta no es JSON válido: " + body, e);
        }
    }

    private String writeJson(Object payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("YunExpress: no se pudo serializar el payload", e);
        }
    }

    static String encodeQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            sb.append(sb.isEmpty() ? '?' : '&')
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** El gateway rechazó el token (401): se distingue para poder reintentar una vez con token nuevo. */
    static class YunExpressAuthException extends RuntimeException {
        YunExpressAuthException(String message) {
            super(message);
        }
    }
}
