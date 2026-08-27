package com.nexaplatform.dropshipping.application.usecase.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexaplatform.dropshipping.application.chat.ChatAnswer;
import com.nexaplatform.dropshipping.application.chat.ChatContext;
import com.nexaplatform.dropshipping.application.chat.ChatProductRef;
import com.nexaplatform.dropshipping.application.chat.PolicyCatalog;
import com.nexaplatform.dropshipping.application.chat.ChatPrompt;
import com.nexaplatform.dropshipping.application.chat.ChatTool;
import com.nexaplatform.dropshipping.application.usecase.ChatUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatMessage;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatProviderException;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatReply;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatRole;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolCall;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bucle de conversación: monta el contexto, pregunta al modelo, ejecuta las
 * herramientas que pida y vuelve a preguntar hasta que hay respuesta para la
 * persona.
 *
 * <p>
 * El historial vive en Redis y NO viaja en la petición del navegador: si el
 * cliente mandara los turnos anteriores, cualquiera podría fabricar turnos del
 * asistente —«antes me prometiste un reembolso»— y el modelo los daría por
 * ciertos.
 */
@Slf4j
@Service
public class ChatUseCaseImpl implements ChatUseCase {

    private static final String CLAVE = "chat:conv:";
    /** Conversación sin sesión: no existe hoy —el endpoint exige token— pero la clave lo contempla. */
    private static final String ANONIMO = "anon";
    private static final String CUPO = "chat:cupo:";
    /** Turnos guardados: seis intercambios. Suficiente para el hilo, acotado para el gasto. */
    private static final int MAX_TURNOS = 12;

    private final ChatProvider provider;
    private final Map<String, ChatTool> tools;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final PolicyCatalog policies;

    @Value("${nexadrop.chat.max-tool-rounds:3}")
    private int maxToolRounds;

    @Value("${nexadrop.chat.history-ttl-minutes:60}")
    private int historyTtlMinutes;

    /** Mensajes al día por cuenta. El freno por minuto acota el pico; este, la factura. */
    @Value("${nexadrop.chat.daily-limit-per-user:100}")
    private int dailyLimitPerUser;

    /**
     * Mensajes al día en toda la plataforma. Es el interruptor de emergencia: aunque alguien
     * reparta el ataque entre cien cuentas, el gasto tiene un techo conocido y el asistente se
     * apaga solo en vez de vaciar la cuenta del proveedor un domingo por la noche.
     */
    @Value("${nexadrop.chat.daily-limit-global:5000}")
    private int dailyLimitGlobal;

    public ChatUseCaseImpl(ChatProvider provider, List<ChatTool> tools, StringRedisTemplate redis,
            ObjectMapper mapper, PolicyCatalog policies) {
        this.provider = provider;
        this.tools = tools.stream().collect(Collectors.toMap(t -> t.spec().name(), Function.identity()));
        this.redis = redis;
        this.mapper = mapper;
        this.policies = policies;
    }

    @Override
    public ChatAnswer ask(String conversationId, String message, ChatContext context) {
        String id = conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString()
                : conversationId;
        if (!provider.available()) {
            return new ChatAnswer(id, null, List.of(), true, null, 0, ChatAnswer.UNAVAILABLE);
        }
        if (cupoAgotado(context)) {
            return new ChatAnswer(id, null, List.of(), true, null, 0, ChatAnswer.QUOTA);
        }

        List<ChatMessage> historial = cargarHistorial(clave(id, context));
        List<ChatMessage> conversacion = new ArrayList<>();
        conversacion.add(ChatMessage.system(ChatPrompt.forLanguage(context.language(), policies.hechos())));
        conversacion.addAll(historial);
        conversacion.add(ChatMessage.user(message));

        List<ChatToolSpec> disponibles = tools.values().stream()
                .filter(t -> t.allowedFor(context))
                .map(ChatTool::spec)
                .toList();

        Set<ChatProductRef> productos = new LinkedHashSet<>();
        Busqueda busqueda = new Busqueda();
        try {
            String texto = conversar(conversacion, disponibles, context, productos, busqueda);
            guardarHistorial(clave(id, context), historial, message, texto);
            return new ChatAnswer(id, texto, List.copyOf(productos), false, busqueda.consulta, busqueda.total,
                    ChatAnswer.OK);
        } catch (ChatProviderException e) {
            // Se degrada en vez de devolver un error: quien está a punto de comprar
            // merece una salida —el canal humano—, no una pantalla rota. El texto de
            // cortesía lo pinta el escaparate, que es quien tiene los ocho idiomas.
            log.warn("El asistente no pudo responder: {}", e.getMessage());
            return new ChatAnswer(id, null, List.of(), true, null, 0, ChatAnswer.UNAVAILABLE);
        }
    }

    /** Lo último que buscó la conversación, para que el escaparate pueda repetirlo en su rejilla. */
    private static final class Busqueda {
        private String consulta;
        private long total;
    }

    /**
     * Cuenta el mensaje contra dos cupos diarios: el de la cuenta y el de toda la plataforma.
     *
     * <p>
     * Si Redis no responde se deja pasar: quedarse sin contador no puede dejar la tienda sin
     * asistente, y el freno por minuto del filtro de peticiones sigue en pie. Es una decisión
     * consciente —proteger el servicio por delante de la factura—, y por eso se registra.
     */
    private boolean cupoAgotado(ChatContext context) {
        String dia = LocalDate.now(ZoneOffset.UTC).toString();
        try {
            Long global = redis.opsForValue().increment(CUPO + "global:" + dia);
            expirarAlDiaSiguiente(CUPO + "global:" + dia, global);
            if (global != null && global > dailyLimitGlobal) {
                log.error("Cupo diario GLOBAL del asistente agotado ({} mensajes): queda apagado hasta mañana",
                        dailyLimitGlobal);
                return true;
            }
            if (context.userId() == null) {
                return false;
            }
            String clave = CUPO + "user:" + context.userId() + ':' + dia;
            Long delUsuario = redis.opsForValue().increment(clave);
            expirarAlDiaSiguiente(clave, delUsuario);
            if (delUsuario != null && delUsuario > dailyLimitPerUser) {
                log.warn("Cupo diario agotado para {}: {} mensajes", context.userId(), dailyLimitPerUser);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("No se pudo contar el cupo del asistente: {}", e.getMessage());
            return false;
        }
    }

    /** Solo al crear el contador: reponerle el plazo en cada mensaje lo volvería eterno. */
    private void expirarAlDiaSiguiente(String clave, Long valor) {
        if (valor != null && valor == 1L) {
            redis.expire(clave, Duration.ofHours(36));
        }
    }

    /**
     * Dónde vive el historial de esta conversación.
     *
     * <p>
     * <b>La clave incluye al dueño.</b> Con solo el identificador de conversación bastaba con
     * conocerlo —o acertarlo— para continuar la charla de otra persona y leer, de paso, lo que sus
     * herramientas hubieran traído: números de pedido, importes, seguimientos. Atándola al usuario,
     * pedir la conversación de otro no da error ni filtra nada: simplemente empieza una vacía.
     */
    private String clave(String conversationId, ChatContext context) {
        String duenyo = context.userId() != null ? context.userId().toString() : ANONIMO;
        return CLAVE + duenyo + ':' + conversationId;
    }

    /** Pregunta y resuelve herramientas hasta que el modelo tenga algo que decir. */
    private String conversar(List<ChatMessage> conversacion, List<ChatToolSpec> disponibles, ChatContext context,
            Set<ChatProductRef> productos, Busqueda busqueda) {
        for (int ronda = 0; ronda < maxToolRounds; ronda++) {
            ChatReply reply = provider.reply(conversacion, disponibles);
            if (!reply.wantsTools()) {
                return reply.text();
            }
            conversacion.add(ChatMessage.assistant(reply.text(), reply.toolCalls()));
            for (ChatToolCall call : reply.toolCalls()) {
                String resultado = ejecutar(call, context);
                recogerProductos(resultado, productos, busqueda);
                conversacion.add(ChatMessage.toolResult(call.id(), resultado));
            }
        }
        // Agotadas las rondas se pide un cierre sin herramientas: es preferible una
        // respuesta imperfecta a dejar a la persona esperando en un bucle.
        return provider.reply(conversacion, List.of()).text();
    }

    private String ejecutar(ChatToolCall call, ChatContext context) {
        ChatTool tool = tools.get(call.name());
        if (tool == null || !tool.allowedFor(context)) {
            return "{\"error\":\"herramienta no disponible\"}";
        }
        try {
            JsonNode argumentos = mapper.readTree(call.argumentsJson());
            return tool.execute(argumentos, context);
        } catch (Exception e) {
            // El fallo de una herramienta se le cuenta al modelo, no se propaga: así
            // puede disculparse o probar otra vía en lugar de tumbar la conversación.
            log.warn("La herramienta {} falló: {}", call.name(), e.getMessage());
            return "{\"error\":\"la consulta no se pudo completar\"}";
        }
    }

    private void recogerProductos(String resultadoJson, Set<ChatProductRef> productos, Busqueda busqueda) {
        try {
            JsonNode root = mapper.readTree(resultadoJson);
            // Solo las búsquedas de catálogo traen `consulta`; un pedido o una condición de envío
            // no tienen nada que repetir en la rejilla, y ahí esto se queda a nulo.
            if (root.hasNonNull("consulta")) {
                busqueda.consulta = root.get("consulta").asText();
                busqueda.total = root.path("total").asLong(0);
            }
            for (JsonNode item : root.path("items")) {
                String slug = item.path("slug").asText(null);
                if (slug != null) {
                    productos.add(new ChatProductRef(slug, item.path("titulo").asText(null),
                            item.path("mainImage").asText(null)));
                }
            }
        } catch (Exception e) {
            log.debug("Resultado sin productos que recoger: {}", e.getMessage());
        }
    }

    private List<ChatMessage> cargarHistorial(String clave) {
        try {
            String crudo = redis.opsForValue().get(clave);
            if (crudo == null || crudo.isBlank()) {
                return new ArrayList<>();
            }
            List<ChatMessage> turnos = new ArrayList<>();
            for (JsonNode turno : mapper.readTree(crudo)) {
                ChatRole role = ChatRole.ASSISTANT.apiValue().equals(turno.path("r").asText()) ? ChatRole.ASSISTANT
                        : ChatRole.USER;
                turnos.add(new ChatMessage(role, turno.path("c").asText(""), null, List.of()));
            }
            return turnos;
        } catch (Exception e) {
            // Sin historial se conversa igual, solo que sin memoria del hilo. Que Redis
            // esté caído no puede impedir vender.
            log.warn("No se pudo leer el historial de {}: {}", clave, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void guardarHistorial(String clave, List<ChatMessage> previos, String pregunta, String respuesta) {
        try {
            ArrayNode array = mapper.createArrayNode();
            List<ChatMessage> turnos = new ArrayList<>(previos);
            turnos.add(ChatMessage.user(pregunta));
            if (respuesta != null && !respuesta.isBlank()) {
                turnos.add(ChatMessage.assistant(respuesta, List.of()));
            }
            List<ChatMessage> recientes = turnos.size() <= MAX_TURNOS ? turnos
                    : turnos.subList(turnos.size() - MAX_TURNOS, turnos.size());
            for (ChatMessage turno : recientes) {
                ObjectNode node = array.addObject();
                node.put("r", turno.role().apiValue());
                node.put("c", turno.content());
            }
            redis.opsForValue().set(clave, array.toString(), Duration.ofMinutes(historyTtlMinutes));
        } catch (Exception e) {
            log.warn("No se pudo guardar el historial de {}: {}", clave, e.getMessage());
        }
    }
}
