package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import java.util.List;

/**
 * Motor conversacional. Se declara como interfaz —igual que
 * {@code TranslationProvider}— para que cambiar de proveedor sea sustituir un
 * bean y no reescribir el caso de uso: el contrato es el mismo para cualquier
 * API compatible con el formato de chat con herramientas.
 */
public interface ChatProvider {

    String name();

    boolean available();

    /**
     * Pide un turno al modelo.
     *
     * @param messages conversación completa, en orden; el primer turno es el del sistema
     * @param tools    herramientas que el modelo puede invocar (puede ir vacía)
     */
    ChatReply reply(List<ChatMessage> messages, List<ChatToolSpec> tools);
}
