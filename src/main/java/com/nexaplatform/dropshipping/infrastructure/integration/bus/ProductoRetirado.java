package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import java.time.Instant;

/**
 * Un producto que deja de estar certificado o se retira del catálogo.
 *
 * <p>Importa tanto como el alta: un cliente que siga vendiendo algo retirado
 * acabará con un pedido que no se puede servir, y el problema lo tendrá con SU
 * comprador. Avisar de la baja es parte del servicio, no un extra.
 *
 * @param motivo por qué se retira, para que el cliente pueda decidir si lo
 *               esconde o lo marca como agotado
 */
public record ProductoRetirado(
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
        String externalId,
        String slug,
        String motivo) {

    public static ProductoRetirado de(String externalId, String slug, String motivo) {
        return new ProductoRetirado(EventoBus.VERSION, "producto.retirado", Instant.now().toString(),
                externalId, slug, motivo);
    }
}
