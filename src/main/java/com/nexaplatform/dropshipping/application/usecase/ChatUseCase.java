package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.chat.ChatAnswer;
import com.nexaplatform.dropshipping.application.chat.ChatContext;

/** Conversación con el asistente de la tienda. */
public interface ChatUseCase {

    /**
     * Responde a un mensaje dentro de una conversación.
     *
     * @param conversationId conversación en curso, o {@code null} para empezar una
     */
    ChatAnswer ask(String conversationId, String message, ChatContext context);
}
