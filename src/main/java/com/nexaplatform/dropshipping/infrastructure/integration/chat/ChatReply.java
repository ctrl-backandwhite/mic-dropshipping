package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import java.util.List;

/**
 * Respuesta de un turno: o texto para la persona, o llamadas a herramientas que
 * hay que resolver antes de volver a preguntar al modelo. Pueden venir ambas.
 */
public record ChatReply(String text, List<ChatToolCall> toolCalls) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
