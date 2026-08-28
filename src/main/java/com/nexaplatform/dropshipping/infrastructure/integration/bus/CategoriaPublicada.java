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
        /**
         * Cuándo ocurrió, en texto ISO-8601 (UTC).
         *
         * <p>Texto y no {@code Instant} a propósito. La bandeja de salida convierte el evento a JSON
         * con el serializador de la aplicación, y ese no sabe escribir los tipos de fecha de Java sin
         * un módulo aparte: al intentarlo fallaba, la transacción se deshacía entera y marcar un
         * producto como verificado dejaba de guardarse. Además, del otro lado del bus puede haber
         * servicios que no son Java, y una fecha ISO en texto la entiende cualquiera.
         */
        String ocurrido,
        String codigo,
        Map<String, String> nombre,
        String padre,
        boolean activa) {

    public static CategoriaPublicada de(String codigo, Map<String, String> nombre, String padre, boolean activa) {
        return new CategoriaPublicada(EventoBus.VERSION, "categoria.publicada", Instant.now().toString(),
                codigo, nombre, padre, activa);
    }
}
