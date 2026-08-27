package com.nexaplatform.dropshipping.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolSpec;

/**
 * Herramienta que el asistente puede invocar. Cada implementación declara su
 * contrato y lo ejecuta contra los casos de uso que ya existen: el asistente no
 * consulta la base de datos, pide las cosas por donde las pide el resto de la
 * aplicación, con sus mismas reglas.
 */
public interface ChatTool {

    ChatToolSpec spec();

    /** ¿Puede usarse en esta conversación? Las que tocan datos personales exigen sesión. */
    default boolean allowedFor(ChatContext context) {
        return true;
    }

    /**
     * Ejecuta la herramienta.
     *
     * @param arguments argumentos ya parseados; nunca confiar en su contenido
     * @return resultado en JSON, que se devuelve al modelo como texto
     */
    String execute(JsonNode arguments, ChatContext context);
}
