package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Un mensaje de la persona al asistente. */
@Data
public class ChatAskDtoIn {

    /** Conversación en curso. Vacío para empezar una nueva. */
    @Size(max = 64)
    private String conversationId;

    /**
     * Tope de 1.000 caracteres: cada mensaje se paga por tokens, y un campo sin
     * límite es una factura sin límite para quien lo descubra.
     */
    @NotBlank
    @Size(max = 1000)
    private String message;

    /** Idioma en el que responder. Por defecto, español. */
    @Size(max = 8)
    private String lang;
}
