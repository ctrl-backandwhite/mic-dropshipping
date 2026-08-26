package com.nexaplatform.dropshipping.infrastructure.integration.chat;

/**
 * Declaración de una herramienta que el modelo puede invocar.
 *
 * @param name             identificador que el modelo devolverá al llamarla
 * @param description      para qué sirve; es lo que el modelo lee para decidir
 * @param parametersSchema esquema JSON de los argumentos, como texto
 */
public record ChatToolSpec(String name, String description, String parametersSchema) {
}
