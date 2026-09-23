package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor conversacional sobre la API de DeepSeek, que habla el formato de chat
 * con herramientas compatible con OpenAI. Se usa {@link HttpClient} del JDK como
 * en el resto de integraciones del proyecto, sin añadir un cliente nuevo.
 *
 * <p>
 * El modelo se deja configurable a propósito: la cuenta expone
 * {@code deepseek-v4-flash} (el barato, por defecto) y {@code deepseek-v4-pro}.
 * Cambiar de uno a otro es una variable de entorno, no un despliegue de código.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nexadrop.chat", name = "enabled", havingValue = "true")
public class DeepSeekChatProvider implements ChatProvider {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${nexadrop.chat.api-key:}")
    private String apiKey;

    @Value("${nexadrop.chat.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${nexadrop.chat.model:deepseek-v4-flash}")
    private String model;

    @Value("${nexadrop.chat.timeout-seconds:30}")
    private int timeoutSeconds;

    /** Tope de la respuesta. Un chat de tienda no necesita más y acota el gasto. */
    @Value("${nexadrop.chat.max-tokens:800}")
    private int maxTokens;

    public DeepSeekChatProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatReply reply(List<ChatMessage> messages, List<ChatToolSpec> tools) {
        if (!available()) {
            throw new ChatProviderException("El asistente no tiene credenciales configuradas");
        }
        String body = buildBody(messages, tools);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // El cuerpo se recorta: puede traer eco de la petición, y ahí viaja
                // la conversación de una persona. En el log no pinta nada.
                throw new ChatProviderException(
                        "El asistente respondió " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            return parseReply(response.body());
        } catch (IOException e) {
            throw new ChatProviderException("No se pudo hablar con el asistente", e);
        } catch (InterruptedException e) {
            // Tragarse la interrupción deja al pool sin enterarse de que le han pedido parar.
            Thread.currentThread().interrupt();
            throw new ChatProviderException("Consulta al asistente interrumpida", e);
        }
    }

    /** Construye el cuerpo de la petición. Visible para pruebas. */
    String buildBody(List<ChatMessage> messages, List<ChatToolSpec> tools) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode node = msgs.addObject();
            node.put("role", m.role().apiValue());
            node.put("content", m.content() == null ? "" : m.content());
            if (m.toolCallId() != null) {
                node.put("tool_call_id", m.toolCallId());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ChatToolCall call : m.toolCalls()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call.id());
                    c.put("type", "function");
                    ObjectNode fn = c.putObject("function");
                    fn.put("name", call.name());
                    fn.put("arguments", call.argumentsJson());
                }
            }
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ChatToolSpec spec : tools) {
                ObjectNode t = toolsNode.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.set("parameters", readSchema(spec));
            }
            root.put("tool_choice", "auto");
        }
        return root.toString();
    }

    /** Interpreta la respuesta. Visible para pruebas. */
    ChatReply parseReply(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode message = root.path("choices").path(0).path("message");
            String text = message.path("content").isTextual() ? message.get("content").asText() : null;
            List<ChatToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                calls.add(new ChatToolCall(call.path("id").asText(), call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText("{}")));
            }
            return new ChatReply(text == null || text.isBlank() ? null : text, calls);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChatProviderException("Respuesta ilegible del asistente", e);
        }
    }

    private JsonNode readSchema(ChatToolSpec spec) {
        try {
            return mapper.readTree(spec.parametersSchema());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ChatProviderException("Esquema inválido en la herramienta " + spec.name(), e);
        }
    }

    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
