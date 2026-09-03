package com.nexaplatform.dropshipping.infrastructure.messaging;

import java.util.Map;
import java.util.UUID;

public record ProductIngestedEvent(UUID productId, String slug, String source, String externalId) {

    /**
     * Construye el evento a partir del mapa que entrega el consumidor.
     *
     * <p>Hace falta porque el consumidor está configurado para deserializar SIEMPRE a un mapa
     * ({@code spring.json.use.type.headers: false} y {@code value.default.type: java.util.HashMap}),
     * de modo que un listener que declare este tipo en su firma nunca lo recibe: recibe un
     * {@code HashMap} y Spring no sabe convertirlo. El síntoma no es que deje de indexarse un
     * producto: es que el listener revienta con cada mensaje del tema y lo reintenta sin fin. El
     * 3-sep-2026 eso llenaba el registro de preproducción y producción a razón de 716 líneas de
     * traza por hilo cada veinte minutos, quemando procesador sin que nadie lo notara.
     *
     * <p>Se convierte aquí, y no en cada listener, porque son dos los que escuchan este tema —el
     * indexador de búsqueda y el traductor— y los dos tenían el mismo fallo.
     *
     * @return el evento, o {@code null} si el mensaje no trae un identificador de producto
     *         utilizable. Devolver null y que el listener lo ignore es deliberado: un mensaje
     *         ilegible en el tema no puede convertirse en un reintento eterno, que es justo lo que
     *         se viene a arreglar.
     */
    public static ProductIngestedEvent desde(Map<String, Object> mensaje) {
        if (mensaje == null) {
            return null;
        }
        UUID id = uuid(mensaje.get("productId"));
        if (id == null) {
            return null;
        }
        return new ProductIngestedEvent(id, texto(mensaje.get("slug")), texto(mensaje.get("source")),
                texto(mensaje.get("externalId")));
    }

    private static UUID uuid(Object valor) {
        if (valor instanceof UUID id) {
            return id;
        }
        if (valor == null) {
            return null;
        }
        try {
            return UUID.fromString(valor.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String texto(Object valor) {
        return valor == null ? null : valor.toString();
    }
}
