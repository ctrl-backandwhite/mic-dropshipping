package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekChatProviderTest {

    private DeepSeekChatProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        provider = new DeepSeekChatProvider(mapper);
        ReflectionTestUtils.setField(provider, "model", "deepseek-v4-flash");
        ReflectionTestUtils.setField(provider, "maxTokens", 800);
        ReflectionTestUtils.setField(provider, "apiKey", "sk-de-prueba");
    }

    @Test
    @DisplayName("Sin clave configurada el proveedor se declara no disponible")
    void sinClaveNoEstaDisponible() {
        ReflectionTestUtils.setField(provider, "apiKey", "");
        assertFalse(provider.available());
        assertThrows(ChatProviderException.class,
                () -> provider.reply(List.of(ChatMessage.user("hola")), List.of()));
    }

    @Test
    @DisplayName("Una respuesta de texto se devuelve sin llamadas a herramientas")
    void respuestaDeTexto() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":"Tenemos varias opciones."}}]}""";

        ChatReply reply = provider.parseReply(json);

        assertEquals("Tenemos varias opciones.", reply.text());
        assertFalse(reply.wantsTools());
    }

    @Test
    @DisplayName("Las llamadas a herramientas se extraen con sus argumentos sin interpretar")
    void respuestaConHerramientas() {
        String json = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{
                     "name":"buscar_productos","arguments":"{\\"consulta\\":\\"zapatillas\\"}"}}]}}]}""";

        ChatReply reply = provider.parseReply(json);

        assertNull(reply.text());
        assertTrue(reply.wantsTools());
        assertEquals(1, reply.toolCalls().size());
        assertEquals("call_1", reply.toolCalls().get(0).id());
        assertEquals("buscar_productos", reply.toolCalls().get(0).name());
        // Los argumentos viajan como texto: el escapado varía entre modelos y solo
        // Jackson debe interpretarlos, nunca una comparación de cadenas.
        assertEquals("{\"consulta\":\"zapatillas\"}", reply.toolCalls().get(0).argumentsJson());
    }

    @Test
    @DisplayName("Una respuesta sin opciones no revienta: se trata como turno vacío")
    void respuestaVacia() {
        ChatReply reply = provider.parseReply("{\"choices\":[]}");

        assertNull(reply.text());
        assertFalse(reply.wantsTools());
    }

    @Test
    @DisplayName("Un cuerpo ilegible se convierte en excepción propia del proveedor")
    void respuestaIlegible() {
        assertThrows(ChatProviderException.class, () -> provider.parseReply("esto no es json"));
    }

    @Test
    @DisplayName("El cuerpo lleva el modelo, los papeles de cada turno y las herramientas declaradas")
    void cuerpoDeLaPeticion() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("Eres el asistente de la tienda."),
                ChatMessage.user("¿Tenéis zapatillas?"));
        ChatToolSpec tool = new ChatToolSpec("buscar_productos", "Busca en el catálogo",
                "{\"type\":\"object\",\"properties\":{\"consulta\":{\"type\":\"string\"}}}");

        String body = provider.buildBody(messages, List.of(tool));
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);

        assertEquals("deepseek-v4-flash", root.get("model").asText());
        assertEquals(800, root.get("max_tokens").asInt());
        assertEquals("system", root.get("messages").get(0).get("role").asText());
        assertEquals("user", root.get("messages").get(1).get("role").asText());
        assertEquals("function", root.get("tools").get(0).get("type").asText());
        assertEquals("buscar_productos", root.get("tools").get(0).get("function").get("name").asText());
        // El esquema viaja como objeto JSON, no como texto escapado: si se enviara
        // como cadena, el modelo no sabría qué argumentos acepta la herramienta.
        assertTrue(root.get("tools").get(0).get("function").get("parameters").isObject());
        assertEquals("auto", root.get("tool_choice").asText());
    }

    @Test
    @DisplayName("El resultado de una herramienta viaja con su identificador de llamada")
    void resultadoDeHerramienta() throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.assistant(null, List.of(new ChatToolCall("call_1", "buscar_productos", "{}"))),
                ChatMessage.toolResult("call_1", "[]"));

        String body = provider.buildBody(messages, List.of());
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);

        assertEquals("call_1", root.get("messages").get(0).get("tool_calls").get(0).get("id").asText());
        assertEquals("tool", root.get("messages").get(1).get("role").asText());
        assertEquals("call_1", root.get("messages").get(1).get("tool_call_id").asText());
    }
}
