package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Cliente HTTP de bajo nivel para la <b>YunExpress Open Platform</b> (云途, open.yunexpress.cn).
 *
 * <p>Análogo a {@link CainiaoLinkClient} pero para YunExpress: la fontanería (firma + POST) vive aquí;
 * el mapeo de negocio (crear envío, tracking) vive en {@link YunExpressFulfillmentService}.
 *
 * <p><b>INACTIVO por defecto.</b> La estructura está lista, pero la <b>firma exacta</b> y las <b>rutas
 * de endpoint</b> dependen de la documentación de YunExpress (open.yunexpress.cn/openApi/doc) y de las
 * credenciales de sandbox (appKey + appSecret), que aún están pendientes de que YunExpress cree la
 * aplicación UAT. Los métodos {@link #sign(String)} e {@link #invoke(String, String)} son placeholders
 * honestos: hay que ajustarlos al algoritmo real (campos que entran, orden, MD5/HMAC, cabecera vs body).
 */
@Slf4j
@Component
public class YunExpressClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.yunexpress.base-url:https://oms.api.yunexpress.cn}")
    private String baseUrl;
    @Value("${nexadrop.yunexpress.app-key:}")
    private String appKey;
    /** Solo por variable de entorno; nunca se loguea. Se usa para firmar la petición. */
    @Value("${nexadrop.yunexpress.app-secret:}")
    private String appSecret;

    public boolean hasCredentials() {
        return appKey != null && !appKey.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /**
     * POST JSON a un endpoint de la API de YunExpress con la firma en cabecera.
     *
     * <p><b>TODO(real):</b> ajustar al esquema EXACTO de YunExpress:
     * <ul>
     *   <li>Cabeceras de auth: normalmente {@code APIKey}/{@code Authorization} + firma (ver doc).</li>
     *   <li>Algoritmo de firma en {@link #sign(String)} (qué se firma y con qué hash).</li>
     *   <li>Ruta base + path del endpoint (crear orden, etiqueta, tracking).</li>
     * </ul>
     *
     * @param path      path del endpoint (p.ej. "/api/WayBill/CreateOrder")
     * @param jsonBody  cuerpo JSON de negocio ya serializado
     * @return cuerpo de la respuesta (JSON) como String
     */
    public String invoke(String path, String jsonBody) {
        String url = baseUrl + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json;charset=UTF-8")
                // TODO(real): cabeceras de autenticación reales de YunExpress (APIKey + firma).
                .header("Authorization", authorizationHeader())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("YunExpress HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("YunExpress: fallo de red llamando a " + path, e);
        }
    }

    /**
     * Cabecera de autorización. YunExpress suele usar Basic sobre appKey:appSecret o un token de firma.
     *
     * <p><b>TODO(real):</b> reemplazar por el esquema documentado. De momento se deja un Basic estándar
     * como punto de partida (appKey:appSecret en Base64), pendiente de confirmar contra la doc/sandbox.
     */
    private String authorizationHeader() {
        String raw = appKey + ":" + appSecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Firma de una petición.
     *
     * <p><b>TODO(real):</b> implementar el algoritmo EXACTO de YunExpress (la pantalla "Signature
     * Verification" del console permite validarlo: dado appSecret + contenido, devuelve el Sign esperado).
     * Típicamente es un MD5/HMAC de los parámetros ordenados + appSecret. Este placeholder NO es el real.
     */
    public String sign(String content) {
        throw new UnsupportedOperationException(
                "YunExpress sign() pendiente: implementar el algoritmo real desde open.yunexpress.cn/openApi/doc");
    }
}
