package com.nexaplatform.dropshipping.infrastructure.integration.chat;

/**
 * Petición del modelo para ejecutar una herramienta. {@code argumentsJson} llega
 * como texto JSON tal cual lo emite el modelo: se parsea siempre con Jackson,
 * nunca comparando cadenas, porque el escapado varía entre modelos y versiones.
 */
public record ChatToolCall(String id, String name, String argumentsJson) {
}
