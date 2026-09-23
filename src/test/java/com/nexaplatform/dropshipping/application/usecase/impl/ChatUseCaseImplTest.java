package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.chat.ChatAnswer;
import com.nexaplatform.dropshipping.application.chat.ChatContext;
import com.nexaplatform.dropshipping.application.chat.ChatTool;
import com.nexaplatform.dropshipping.application.chat.PolicyCatalog;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatMessage;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatProviderException;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatReply;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatRole;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolCall;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatUseCaseImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;
    private final ChatContext contexto = new ChatContext(null, "es");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = Mockito.mock(StringRedisTemplate.class);
        valores = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(valores);
    }

    private ChatUseCaseImpl useCase(ChatProvider provider, List<ChatTool> tools) {
        ChatUseCaseImpl impl = new ChatUseCaseImpl(provider, tools, redis, mapper, new PolicyCatalog());
        ReflectionTestUtils.setField(impl, "maxToolRounds", 3);
        ReflectionTestUtils.setField(impl, "historyTtlMinutes", 60);
        ReflectionTestUtils.setField(impl, "dailyLimitPerUser", 100);
        ReflectionTestUtils.setField(impl, "dailyLimitGlobal", 5000);
        return impl;
    }

    /** Proveedor de mentira que va soltando respuestas preparadas. */
    private static class ProveedorFalso implements ChatProvider {
        private final List<ChatReply> guion = new ArrayList<>();
        private final List<List<ChatMessage>> recibido = new ArrayList<>();
        private boolean disponible = true;
        private RuntimeException fallo;

        @Override
        public String name() {
            return "falso";
        }

        @Override
        public boolean available() {
            return disponible;
        }

        @Override
        public ChatReply reply(List<ChatMessage> messages, List<ChatToolSpec> tools) {
            recibido.add(List.copyOf(messages));
            if (fallo != null) {
                throw fallo;
            }
            return guion.isEmpty() ? new ChatReply("fin", List.of()) : guion.remove(0);
        }
    }

    /** Herramienta de mentira que devuelve dos productos. */
    private static class HerramientaFalsa implements ChatTool {
        private JsonNode argumentosRecibidos;

        @Override
        public ChatToolSpec spec() {
            return new ChatToolSpec("buscar_productos", "busca", "{\"type\":\"object\"}");
        }

        @Override
        public String execute(JsonNode arguments, ChatContext context) {
            this.argumentosRecibidos = arguments;
            return "{\"items\":[{\"slug\":\"zapatilla-runner\",\"titulo\":\"Zapatilla Runner\"},{\"slug\":\"bota-trail\",\"titulo\":\"Bota Trail\"}]}";
        }
    }

    @Test
    @DisplayName("Con el cupo diario agotado se avisa del motivo, sin llamar al modelo")
    void cupoAgotado() {
        ProveedorFalso proveedor = new ProveedorFalso();
        Mockito.when(valores.increment(Mockito.startsWith("chat:cupo:global:"))).thenReturn(9_999L);

        ChatAnswer answer = useCase(proveedor, List.of()).ask(null, "hola", contexto);

        assertTrue(answer.degraded());
        assertEquals("QUOTA", answer.reason());
        // Lo importante: no se ha gastado ni un token en el proveedor.
        assertTrue(proveedor.recibido.isEmpty());
    }

    @Test
    @DisplayName("El cupo de una cuenta agotado no apaga el asistente para las demás")
    void cupoDeUnaCuenta() {
        ProveedorFalso proveedor = new ProveedorFalso();
        Mockito.when(valores.increment(Mockito.startsWith("chat:cupo:global:"))).thenReturn(10L);
        Mockito.when(valores.increment(Mockito.startsWith("chat:cupo:user:"))).thenReturn(101L);

        ChatAnswer answer = useCase(proveedor, List.of()).ask(null, "hola",
                new ChatContext(java.util.UUID.randomUUID(), "es"));

        assertEquals("QUOTA", answer.reason());
        assertTrue(proveedor.recibido.isEmpty());
    }

    @Test
    @DisplayName("Si Redis no responde se sigue atendiendo: el contador no puede tumbar la tienda")
    void redisCaidoNoBloquea() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion.add(new ChatReply("Aquí estoy", List.of()));
        Mockito.when(valores.increment(Mockito.anyString())).thenThrow(new RuntimeException("redis caído"));

        ChatAnswer answer = useCase(proveedor, List.of()).ask(null, "hola", contexto);

        assertEquals("Aquí estoy", answer.reply());
        assertEquals("OK", answer.reason());
    }

    @Test
    @DisplayName("La conversación de otra persona no se lee aunque se conozca su identificador")
    void conversacionAjena() {
        java.util.UUID ana = java.util.UUID.randomUUID();
        java.util.UUID beto = java.util.UUID.randomUUID();
        // Lo que Ana tiene guardado en SU conversación.
        Mockito.when(valores.get("chat:conv:" + ana + ":conv-secreta"))
                .thenReturn("[{\"r\":\"user\",\"c\":\"mi pedido NX-777\"}]");
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion.add(new ChatReply("Hola", List.of()));

        // Beto pide esa misma conversación por su identificador.
        useCase(proveedor, List.of()).ask("conv-secreta", "¿qué decíamos?", new ChatContext(beto, "es"));

        // No hereda nada: solo el turno del sistema y su propia pregunta.
        List<ChatMessage> enviado = proveedor.recibido.get(0);
        assertEquals(2, enviado.size());
        assertEquals(ChatRole.SYSTEM, enviado.get(0).role());
        assertEquals("¿qué decíamos?", enviado.get(1).content());
    }

    @Test
    @DisplayName("Sin proveedor disponible se responde degradado, sin texto inventado")
    void proveedorNoDisponible() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.disponible = false;

        ChatAnswer answer = useCase(proveedor, List.of()).ask(null, "hola", contexto);

        assertTrue(answer.degraded());
        assertNull(answer.reply());
        assertNotNull(answer.conversationId());
    }

    @Test
    @DisplayName("Una respuesta directa se devuelve y la conversación queda identificada")
    void respuestaDirecta() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion.add(new ChatReply("Buenos días", List.of()));

        ChatAnswer answer = useCase(proveedor, List.of()).ask(null, "hola", contexto);

        assertFalse(answer.degraded());
        assertEquals("Buenos días", answer.reply());
        assertNotNull(answer.conversationId());
        // El primer turno enviado al modelo es SIEMPRE el del sistema.
        assertEquals(ChatRole.SYSTEM, proveedor.recibido.get(0).get(0).role());
        // El tipo va explícito: ValueOperations.set tiene dos sobrecargas de tres argumentos
        // (Duration y Consumer) y un any() suelto no sabe a cuál se refiere.
        Mockito.verify(valores).set(Mockito.startsWith("chat:conv:"), Mockito.anyString(),
                Mockito.any(java.time.Duration.class));
    }

    @Test
    @DisplayName("Cuando el modelo pide una herramienta, se ejecuta y se recogen los productos")
    void ejecutaHerramienta() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion.add(new ChatReply(null,
                List.of(new ChatToolCall("call_1", "buscar_productos", "{\"consulta\":\"zapatillas\"}"))));
        proveedor.guion.add(new ChatReply("He encontrado dos.", List.of()));
        HerramientaFalsa herramienta = new HerramientaFalsa();

        ChatAnswer answer = useCase(proveedor, List.of(herramienta)).ask(null, "busco zapatillas", contexto);

        assertEquals("He encontrado dos.", answer.reply());
        assertEquals(2, answer.products().size());
        assertEquals("zapatilla-runner", answer.products().get(0).slug());
        assertEquals("Zapatilla Runner", answer.products().get(0).title());
        assertEquals("zapatillas", herramienta.argumentosRecibidos.path("consulta").asText());
        // La segunda vuelta lleva el resultado de la herramienta con su identificador.
        List<ChatMessage> segunda = proveedor.recibido.get(1);
        assertEquals(ChatRole.TOOL, segunda.get(segunda.size() - 1).role());
        assertEquals("call_1", segunda.get(segunda.size() - 1).toolCallId());
    }

    @Test
    @DisplayName("Una herramienta que el modelo se inventa no rompe la conversación")
    void herramientaDesconocida() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion
                .add(new ChatReply(null, List.of(new ChatToolCall("call_1", "herramienta_que_no_existe", "{}"))));
        proveedor.guion.add(new ChatReply("Disculpa, no puedo con eso.", List.of()));

        ChatAnswer answer = useCase(proveedor, List.of(new HerramientaFalsa())).ask(null, "haz algo", contexto);

        assertEquals("Disculpa, no puedo con eso.", answer.reply());
        assertFalse(answer.degraded());
    }

    @Test
    @DisplayName("Si el motor falla se degrada en vez de devolver un error a quien está comprando")
    void fallosDelMotor() {
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.fallo = new ChatProviderException("502 del proveedor");

        ChatAnswer answer = useCase(proveedor, List.of()).ask("conv-1", "hola", contexto);

        assertTrue(answer.degraded());
        assertNull(answer.reply());
        assertEquals("conv-1", answer.conversationId());
    }

    @Test
    @DisplayName("El historial guardado en Redis se recupera en el turno siguiente")
    void recuperaHistorial() {
        Mockito.when(valores.get("chat:conv:anon:conv-1"))
                .thenReturn("[{\"r\":\"user\",\"c\":\"tenéis zapatillas\"},{\"r\":\"assistant\",\"c\":\"sí\"}]");
        ProveedorFalso proveedor = new ProveedorFalso();
        proveedor.guion.add(new ChatReply("Del 39 al 45.", List.of()));

        useCase(proveedor, List.of()).ask("conv-1", "¿y tallas?", contexto);

        List<ChatMessage> enviado = proveedor.recibido.get(0);
        // sistema + dos turnos del historial + la pregunta nueva
        assertEquals(4, enviado.size());
        assertEquals("tenéis zapatillas", enviado.get(1).content());
        assertEquals(ChatRole.ASSISTANT, enviado.get(2).role());
    }
}
