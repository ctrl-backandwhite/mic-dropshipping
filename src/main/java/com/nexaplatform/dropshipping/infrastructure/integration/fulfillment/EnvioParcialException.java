package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;

import java.io.Serial;
import java.util.List;

/**
 * Un pedido repartido en varios bultos falló a mitad, y los anteriores YA tienen guía emitida.
 *
 * <p>Sin esto, la excepción del bulto que falla se llevaba por delante las guías de los bultos 1..n−1:
 * existían y estaban pagadas en el transportista, pero no se persistía ninguna, así que no aparecían ni en
 * el pedido, ni en {@code order_shipment}, ni en el registro. El javadoc del adaptador afirmaba que «las
 * guías ya creadas se pueden anular desde el panel»; no se podía, porque el panel no las veía.
 *
 * <p>Lleva dentro lo ya creado para que quien orquesta lo guarde ANTES de registrar el fallo. El pedido
 * sigue yendo a la bandeja de incidencias —no está despachado—, pero las etiquetas quedan a la vista de
 * quien tenga que anularlas o reaprovecharlas.
 */
public class EnvioParcialException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<FulfillmentResult> yaCreados;

    public EnvioParcialException(List<FulfillmentResult> yaCreados, RuntimeException causa) {
        super("Envío parcial: " + yaCreados.size() + " bulto(s) con guía antes del fallo", causa);
        this.yaCreados = List.copyOf(yaCreados);
    }

    /** Las guías que SÍ se emitieron antes del fallo. Nunca se descartan. */
    public List<FulfillmentResult> yaCreados() {
        return yaCreados;
    }
}
