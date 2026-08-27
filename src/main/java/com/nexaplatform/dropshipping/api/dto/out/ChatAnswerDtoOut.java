package com.nexaplatform.dropshipping.api.dto.out;

import com.nexaplatform.dropshipping.application.chat.ChatProductRef;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/** Respuesta del asistente para el escaparate. */
@Value
@Builder
public class ChatAnswerDtoOut {

    String conversationId;

    /** Texto para la persona. Nulo si el asistente no pudo responder. */
    String reply;

    /**
     * Productos encontrados durante el turno. El escaparate los pinta con su
     * ficha de siempre: el precio no pasa por el asistente en ningún momento.
     */
    List<ChatProductRef> products;

    /** true si no hay respuesta: el escaparate pinta su propio aviso, ya traducido. */
    boolean degraded;

    /** Por qué no la hay: OK, UNAVAILABLE o QUOTA. Decide QUÉ aviso pinta el escaparate. */
    String reason;

    /** Términos con los que se buscó, para repetir la búsqueda en el catálogo. Nulo si no hubo. */
    String searchQuery;

    /** Cuántos productos encontró la búsqueda en total (el panel solo enseña los primeros). */
    long searchTotal;
}
