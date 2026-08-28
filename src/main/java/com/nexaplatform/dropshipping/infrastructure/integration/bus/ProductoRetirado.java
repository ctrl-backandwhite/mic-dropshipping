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
        Instant ocurrido,
        String externalId,
        String slug,
        String motivo) {

    public static ProductoRetirado de(String externalId, String slug, String motivo) {
        return new ProductoRetirado(EventoBus.VERSION, "producto.retirado", Instant.now(),
                externalId, slug, motivo);
    }
}
