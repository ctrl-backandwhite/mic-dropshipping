package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Habla con las dos puertas de autenticación de CJ.
 *
 * <p>La lectura de la respuesta está en un método estático ({@link #leer}) y no escondida dentro de la
 * llamada HTTP, para poder probarla con las respuestas reales de CJ sin levantar un servidor. Ahí es
 * donde están los detalles que la documentación no cuenta: el {@code openId} viaja como número, las
 * fechas traen el huso de China y el token pasa de quinientos caracteres.
 */
@Slf4j
@Component
public class CjHttpTokenClient implements CjTokenClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.cj.base-url:https://developers.cjdropshipping.com}")
    private String baseUrl;

    @Value("${nexadrop.cj.auth-timeout-seconds:20}")
    private int timeoutSegundos;

    @Override
    public CjToken obtenerConApiKey(String apiKey) {
        return leer(llamar("/api2.0/v1/authentication/getAccessToken",
                "{\"apiKey\":\"" + apiKey + "\"}"));
    }

    @Override
    public CjToken refrescar(String refreshToken) {
        return leer(llamar("/api2.0/v1/authentication/refreshAccessToken",
                "{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    /**
     * Convierte la respuesta de CJ en un token.
     *
     * <p>Se exige {@code code == 200} <b>y</b> {@code result == true}: CJ contesta 200 de HTTP también
     * cuando rechaza la clave, y quedarse solo con el código HTTP daría por buena una autenticación
     * fallida y guardaría un token vacío que fallaría mucho más tarde, sin pista de por qué.
     */
    static CjToken leer(String cuerpo) {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(cuerpo);
        } catch (IOException e) {
            throw new IllegalStateException("CJ devolvió una respuesta que no se entiende.", e);
        }
        int codigo = raiz.path("code").asInt();
        boolean correcto = raiz.path("result").asBoolean();
        JsonNode datos = raiz.path("data");
        if (codigo != 200 || !correcto || datos.isMissingNode() || datos.isNull()) {
            throw new IllegalStateException("CJ rechazó la autenticación (código " + codigo + "): "
                    + raiz.path("message").asText("sin mensaje"));
        }
        return new CjToken(
                texto(datos, "accessToken"),
                texto(datos, "refreshToken"),
                // asText() sobre un número devuelve su representación, que es justo lo que hace falta:
                // el openId llega como 33689 y se usa como cadena para firmar el webhook.
                texto(datos, "openId"),
                fecha(datos, "refreshTokenExpiryDate"));
    }

    private static String texto(JsonNode datos, String campo) {
        JsonNode valor = datos.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asText();
    }

    /**
     * Lee una fecha de CJ respetando su huso.
     *
     * <p>Llegan como {@code 2027-02-14T13:31:52+08:00}. Tomarlas por UTC adelantaría ocho horas el
     * momento a partir del cual el refresco deja de valer.
     */
    private static Instant fecha(JsonNode datos, String campo) {
        String valor = texto(datos, campo);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(valor).toInstant();
        } catch (RuntimeException e) {
            log.warn("Fecha de CJ ilegible en {}: {}", campo, valor);
            return null;
        }
    }

    private String llamar(String ruta, String cuerpo) {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ruta))
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build();
        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            return respuesta.body();
        } catch (IOException e) {
            // El mensaje NO incluye el cuerpo: lleva la clave dentro y acabaría en el registro.
            throw new IllegalStateException("No se pudo contactar con CJ para autenticarse.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Autenticación contra CJ interrumpida.", e);
        }
    }
}
