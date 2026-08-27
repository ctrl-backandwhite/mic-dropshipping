package com.nexaplatform.dropshipping.infrastructure.integration.chat;

/**
 * Fallo hablando con el motor conversacional. Se distingue del resto de errores
 * para que el caso de uso pueda degradar con elegancia —ofrecer el canal
 * humano— en vez de devolver un 500 a quien está intentando comprar.
 */
public class ChatProviderException extends RuntimeException {

    public ChatProviderException(String message) {
        super(message);
    }

    public ChatProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
