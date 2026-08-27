package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Proveedor de reserva cuando el asistente está apagado o sin credenciales.
 * No inventa una respuesta: declara que no está disponible para que el caso de
 * uso devuelva el aviso de «ahora mismo no puedo atenderte» y la persona vaya a
 * un canal humano. Un mock que responde algo plausible en producción es peor que
 * un silencio honesto.
 */
@Component
@ConditionalOnMissingBean(ChatProvider.class)
public class NoopChatProvider implements ChatProvider {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ChatReply reply(List<ChatMessage> messages, List<ChatToolSpec> tools) {
        return new ChatReply(null, List.of());
    }
}
