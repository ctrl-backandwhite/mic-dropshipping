package com.nexaplatform.dropshipping.infrastructure.integration.chat;

/**
 * Papeles de un turno de conversación, con el valor exacto que espera la API.
 * Enum y no constantes sueltas: el conjunto es cerrado y así no se cuela un
 * literal mal escrito que la API rechazaría en tiempo de ejecución.
 */
public enum ChatRole {

    SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool");

    private final String apiValue;

    ChatRole(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }
}
