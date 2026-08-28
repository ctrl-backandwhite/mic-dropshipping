package com.nexaplatform.dropshipping.infrastructure.integration.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Traduce con DeepSeek, la MISMA cuenta, clave y modelo que el asistente del escaparate: una sola
 * factura y un solo sitio donde cambiar de modelo.
 *
 * <p>Frente al traductor automático de Google, un modelo de lenguaje entiende el contexto: sabe que
 * en una ficha de producto «春装» es «ropa de primavera» y no «traje de resorte». Es lo que se
 * necesita para títulos y para las reseñas que escriben personas, donde la traducción literal se
 * nota y espanta.
 */
@Component
@ConditionalOnProperty(prefix = "nexadrop.translation", name = "enabled", havingValue = "true")
@ConditionalOnExpression("'${nexadrop.translation.provider:deepseek}'.equals('deepseek')")
public class DeepSeekTranslationProvider implements TranslationProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekTranslationProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Nombre del idioma en la instrucción. Un código ISO suelto ("pt") el modelo lo interpreta a su
     * manera; el nombre escrito no deja lugar a dudas, y en portugués importa cuál de los dos es.
     */
    private static final Map<String, String> IDIOMAS = Map.of(
            "es", "español de España",
            "en", "inglés",
            "pt", "portugués de Portugal",
            "fr", "francés",
            "de", "alemán",
            "it", "italiano",
            "nl", "neerlandés",
            "zh", "chino simplificado");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.chat.api-key:}")
    private String apiKey;

    @Value("${nexadrop.chat.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${nexadrop.chat.model:deepseek-v4-flash}")
    private String model;

    @Value("${nexadrop.translation.timeout-seconds:60}")
    private int timeoutSeconds;

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // Sin clave NO se devuelve el original: eso guardaría el chino en el campo del español y el
        // catálogo saldría en chino sin que saltara ningún error. Mejor fallar y que se vea.
        if (!available()) {
            throw new IllegalStateException("Falta nexadrop.chat.api-key: no se puede traducir con DeepSeek");
        }
        try {
            HttpResponse<String> respuesta = http.send(
                    peticion(text, sourceLang, targetLang),
                    HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "DeepSeek respondió " + respuesta.statusCode() + ": " + abreviar(respuesta.body()));
            }
            return extraer(respuesta.body(), text);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo traducir con DeepSeek", e);
        } catch (InterruptedException e) {
            // Restaurar la marca es obligatorio: tragársela deja el hilo sin saber que le pidieron parar.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Traducción interrumpida", e);
        }
    }

    private HttpRequest peticion(String texto, String origen, String destino) {
        ObjectNode cuerpo = MAPPER.createObjectNode();
        cuerpo.put("model", model);
        // Temperatura 0: el mismo título tiene que traducirse igual en cada entorno y en cada pasada.
        // Con temperatura alta, dos ejecuciones darían textos distintos para el mismo producto.
        cuerpo.put("temperature", 0);
        ArrayNode mensajes = cuerpo.putArray("messages");
        ObjectNode sistema = mensajes.addObject();
        sistema.put("role", "system");
        sistema.put("content", instruccion(origen, destino));
        ObjectNode usuario = mensajes.addObject();
        usuario.put("role", "user");
        usuario.put("content", texto);
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo.toString()))
                .build();
    }

    /**
     * La instrucción es deliberadamente estrecha. Un modelo al que se le pide «traduce» tiende a
     * saludar, a explicar sus decisiones o a envolver la respuesta en comillas, y todo eso acabaría
     * pintado tal cual en la ficha del producto.
     */
    private String instruccion(String origen, String destino) {
        String nombreOrigen = IDIOMAS.getOrDefault(origen, origen);
        String nombreDestino = IDIOMAS.getOrDefault(destino, destino);
        return """
                Traduce del %s al %s. Es el texto de una tienda de ropa y complementos: puede ser el \
                título de un producto, su descripción o la reseña que ha escrito un cliente.

                Reglas:
                - Responde ÚNICAMENTE con la traducción. Nada de saludos, comillas, notas ni comentarios.
                - Conserva las cifras, las unidades, las tallas y los nombres de marca tal cual.
                - Mantén el registro del original: una reseña de un cliente suena a persona, no a folleto.
                - Si el texto ya está en %s, devuélvelo sin cambios.
                - No inventes ni añadas información que no esté en el original.""".formatted(
                nombreOrigen, nombreDestino, nombreDestino);
    }

    /** Visible para la prueba, igual que el parseo del asistente. */
    String extraer(String cuerpo, String original) {
        try {
            JsonNode raiz = MAPPER.readTree(cuerpo);
            JsonNode contenido = raiz.path("choices").path(0).path("message").path("content");
            if (contenido.isMissingNode() || contenido.asText("").isBlank()) {
                throw new IllegalStateException("DeepSeek devolvió una traducción vacía");
            }
            return limpiar(contenido.asText());
        } catch (IOException e) {
            log.warn("Respuesta de DeepSeek ilegible al traducir «{}»", abreviar(original));
            throw new IllegalStateException("Respuesta de DeepSeek ilegible", e);
        }
    }

    /**
     * Aun con la instrucción, de vez en cuando envuelve la respuesta en comillas. Se quitan solo si
     * abren Y cierran: un título que legítimamente acaba en comillas no se toca.
     */
    String limpiar(String s) {
        String limpio = s.strip();
        if (limpio.length() > 1
                && (limpio.startsWith("\"") && limpio.endsWith("\"")
                        || limpio.startsWith("«") && limpio.endsWith("»"))) {
            return limpio.substring(1, limpio.length() - 1).strip();
        }
        return limpio;
    }

    private static String abreviar(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
