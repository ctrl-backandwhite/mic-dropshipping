package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

/**
 * Cliente de transporte del <b>gateway Cainiao Link</b> ({@code .../gateway/link.do}).
 *
 * <p>Implementa SOLO la fontanería reutilizable e independiente del producto logístico:
 * <ul>
 *   <li><b>Firma</b>: {@code data_digest = Base64( MD5( logistics_interface + appSecret ) )}.</li>
 *   <li><b>Transporte</b>: POST {@code application/x-www-form-urlencoded} (UTF-8) con los
 *       parámetros estándar del gateway Link.</li>
 * </ul>
 *
 * <p>El mapeo de cada API concreta ({@code msg_type} + JSON de negocio + parseo de la respuesta)
 * NO vive aquí, sino en {@link CainiaoFulfillmentService}, porque depende del producto contratado
 * (Global Logistics Solution / CGS / etc.).
 *
 * <p>Entornos (se fija con {@code nexadrop.cainiao.base-url}):
 * <ul>
 *   <li>Sandbox/Daily: {@code https://linkdaily.tbsandbox.com/gateway/link.do}</li>
 *   <li>Pre-producción: {@code https://prelink.cainiao.com/gateway/link.do}</li>
 *   <li>Producción: {@code https://link.cainiao.com/gateway/link.do}</li>
 * </ul>
 */
@Slf4j
@Component
public class CainiaoLinkClient {

    @Value("${nexadrop.cainiao.base-url:https://linkdaily.tbsandbox.com/gateway/link.do}")
    private String baseUrl;
    /** Tu identificador de proveedor en Cainiao = el {@code appKey} de la consola (param {@code logistic_provider_id}). */
    @Value("${nexadrop.cainiao.app-key:}")
    private String appKey;
    /** Secreto de la app (firma las peticiones). SOLO por variable de entorno, nunca en el repo. */
    @Value("${nexadrop.cainiao.app-secret:}")
    private String appSecret;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Invoca una API del gateway Link.
     *
     * @param msgType            nombre de la API ({@code msg_type}) del producto contratado.
     * @param logisticsInterface JSON de negocio ya serializado (lo que se firma).
     * @param toCode             código del CP/destino que exige la API ({@code to_code}); vacío si no aplica.
     * @return el cuerpo de la respuesta (JSON) tal cual; el llamador lo parsea.
     */
    public String invoke(String msgType, String logisticsInterface, String toCode) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("Cainiao: faltan nexadrop.cainiao.app-key / app-secret");
        }
        String body = form(
                "msg_type", msgType,
                "logistic_provider_id", appKey,
                "to_code", toCode == null ? "" : toCode,
                "logistics_interface", logisticsInterface,
                "data_digest", sign(logisticsInterface));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("Cainiao Link " + msgType + " HTTP " + res.statusCode() + ": " + res.body());
            }
            log.debug("Cainiao Link {} OK", msgType);
            return res.body();
        } catch (IOException e) {
            throw new IllegalStateException("Cainiao Link " + msgType + " falló (I/O)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cainiao Link " + msgType + " interrumpido", e);
        }
    }

    /**
     * Verifica la firma de un push ENTRANTE de Cainiao (webhooks): el {@code data_digest} recibido debe
     * coincidir con la firma calculada sobre el {@code logistics_interface}. Comparación en tiempo
     * constante. Fail-closed: si falta el digest o no coincide, devuelve false.
     */
    public boolean verify(String logisticsInterface, String dataDigest) {
        if (logisticsInterface == null || dataDigest == null || dataDigest.isBlank()
                || appSecret == null || appSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sign(logisticsInterface).getBytes(StandardCharsets.UTF_8),
                dataDigest.getBytes(StandardCharsets.UTF_8));
    }

    /** Firma del gateway Link: {@code Base64( MD5( logistics_interface + appSecret ) )}. */
    String sign(String logisticsInterface) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest((logisticsInterface + appSecret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 no disponible en la JVM", e);
        }
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
