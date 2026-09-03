package com.nexaplatform.dropshipping.domain.enums;

/**
 * En qué punto está el anuncio de un producto al bus de integración.
 *
 * <p>Existe porque marcar un producto como verificado tardaba segundos: no por esperar a Kafka —el
 * envío ya iba diferido— sino porque la petición construía la ficha entera para el evento antes de
 * responder. Ahora la petición solo deja la marca y un barrido hace el trabajo.
 *
 * <p>La columna nula significa «no hay nada que anunciar», que es el estado de la inmensa mayoría de
 * los productos.
 */
public enum BusAnuncioEstado {

    /** Hay un cambio que contar al bus. Lo dejó la petición, dentro de su misma transacción. */
    PENDIENTE,

    /** Ya se construyó la ficha y se dejó en la bandeja de salida. */
    ANUNCIADO,

    /**
     * No se pudo construir o publicar, y ya agotó sus intentos. El motivo queda en {@code bus_error}
     * para poder enseñarlo en el panel: un producto que no llega a producción tiene que verse.
     */
    FALLIDO
}
