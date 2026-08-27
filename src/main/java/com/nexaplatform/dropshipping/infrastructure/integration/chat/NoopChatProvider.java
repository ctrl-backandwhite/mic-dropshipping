package com.nexaplatform.dropshipping.infrastructure.integration.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
// La condición es la INVERSA de la del proveedor real, no un @ConditionalOnMissingBean.
//
// Sobre un @Component escaneado, @ConditionalOnMissingBean no es fiable: el orden
// en que se evalúan los componentes no está garantizado, así que este suplente
// podía no registrarse nunca. Y cuando eso pasaba no fallaba el chat: fallaba el
// ARRANQUE ENTERO, porque el caso de uso exige un ChatProvider en su constructor.
//
// Con las dos condiciones enfrentadas —una con enabled=true y esta con
// enabled=false o ausente— siempre existe exactamente uno, cualquiera que sea el
// orden.
@ConditionalOnProperty(prefix = "nexadrop.chat", name = "enabled",
                       havingValue = "false", matchIfMissing = true)
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
