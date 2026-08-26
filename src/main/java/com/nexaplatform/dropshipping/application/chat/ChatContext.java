package com.nexaplatform.dropshipping.application.chat;

import java.util.UUID;

/**
 * Quién pregunta y en qué idioma. Las herramientas que tocan datos personales
 * deben filtrar SIEMPRE por {@code userId} —el del token de seguridad— y jamás
 * por un identificador que venga en los argumentos del modelo: un número de
 * pedido dictado por la conversación es exactamente el camino a leer el pedido
 * de otra persona.
 *
 * @param userId   usuario autenticado, o {@code null} si es una visita anónima
 * @param language código ISO del idioma en que se responde
 */
public record ChatContext(UUID userId, String language) {

    public boolean anonymous() {
        return userId == null;
    }
}
