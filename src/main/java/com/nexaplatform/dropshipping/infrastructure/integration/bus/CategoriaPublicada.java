package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import java.time.Instant;
import java.util.Map;

/**
 * Una categoría lista para existir en los demás entornos.
 *
 * @param version   formato del mensaje; ver {@link EventoBus#VERSION}
 * @param evento    nombre corto, para poder distinguirlos en un vistazo
 * @param ocurrido  cuándo pasó, no cuándo se envió
 * @param codigo    identificador ESTABLE entre entornos. Nunca el UUID: los
 *                  identificadores internos son propios de cada base, y usarlos
 *                  dejaría los productos colgando de una categoría inexistente
 * @param nombre    traducciones por idioma ({@code es}, {@code en}, {@code zh}…)
 * @param padre     código de la categoría superior, o {@code null} si es raíz
 * @param activa    si debe verse en la tienda
 */
public record CategoriaPublicada(
        int version,
        String evento,
        Instant ocurrido,
        String codigo,
        Map<String, String> nombre,
        String padre,
        boolean activa) {

    public static CategoriaPublicada de(String codigo, Map<String, String> nombre, String padre, boolean activa) {
        return new CategoriaPublicada(EventoBus.VERSION, "categoria.publicada", Instant.now(),
                codigo, nombre, padre, activa);
    }
}
