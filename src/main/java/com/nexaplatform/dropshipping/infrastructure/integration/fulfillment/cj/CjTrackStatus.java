package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.ShipmentEventMessage;

/**
 * Estados de seguimiento de CJ ({@code trackingStatus} de {@code /logistic/trackInfo}) y su
 * equivalencia con {@link OrderStatus}.
 *
 * <p>La tabla se escribe entera y cerrada —igual que la de {@code YunExpressTrackNode}— para que un
 * estado que CJ añada mañana se <b>detecte</b> en vez de colarse: {@link #desde(String)} devuelve
 * {@code null} y quien llama lo anota y no toca el pedido. La tentación es tratar lo desconocido como
 * «en tránsito», y sale caro en los dos sentidos: si lo desconocido era una devolución, el pedido se
 * queda enviado para siempre; si se tratara como entregado, se cerrarían pedidos que aún viajan.
 *
 * <p>La correspondencia es deliberadamente conservadora: solo {@link #ENTREGADO} entrega el pedido, y
 * solo {@link #CREADO} —que es el alta en CJ, sin escaneo todavía— se queda en
 * {@link OrderStatus#FORWARDED}. Una incidencia <b>no retrocede</b> el estado: el paquete sigue en
 * circulación y el histórico ya enseña lo que pasó.
 *
 * <p>Cada estado lleva además el {@link ShipmentEventMessage} con el que se guarda el hito, porque el
 * texto de CJ llega en inglés y <b>no se le enseña al cliente tal cual</b>: el timeline guarda la
 * descripción en español y {@code OrderEmailService} la traduce buscando ese texto español.
 */
public enum CjTrackStatus {

    CREADO("Created", OrderStatus.FORWARDED, ShipmentEventMessage.REGISTERED),
    RECOGIDO("Picked up", OrderStatus.SHIPPED, ShipmentEventMessage.PICKED_UP),
    EN_TRANSITO("In transit", OrderStatus.SHIPPED, ShipmentEventMessage.IN_TRANSIT),
    LLEGADA_DESTINO("Arrived at destination", OrderStatus.SHIPPED, ShipmentEventMessage.ARRIVED_COUNTRY),
    EN_REPARTO("Out for delivery", OrderStatus.SHIPPED, ShipmentEventMessage.OUT_FOR_DELIVERY),
    ENTREGADO("Delivered", OrderStatus.DELIVERED, ShipmentEventMessage.DELIVERED),
    /**
     * Incidencia. Va <b>sin mensaje para el cliente</b> a propósito: no hay texto traducido para esto y
     * reutilizar el de «en tránsito» sería decirle que el paquete avanza cuando CJ está avisando de lo
     * contrario. Quien lo lea decide qué hacer; el pedido, mientras tanto, no se mueve de enviado.
     */
    INCIDENCIA("Exception", OrderStatus.SHIPPED, null);

    private final String textoCj;
    private final OrderStatus estadoPedido;
    private final ShipmentEventMessage mensaje;

    CjTrackStatus(String textoCj, OrderStatus estadoPedido, ShipmentEventMessage mensaje) {
        this.textoCj = textoCj;
        this.estadoPedido = estadoPedido;
        this.mensaje = mensaje;
    }

    /** El {@code trackingStatus} tal cual lo escribe CJ, en inglés. */
    public String textoCj() {
        return textoCj;
    }

    /** Estado del pedido que implica este hito. */
    public OrderStatus estadoPedido() {
        return estadoPedido;
    }

    /** Mensaje traducido a los ocho idiomas con el que se guarda el hito; {@code null} si no hay. */
    public ShipmentEventMessage mensaje() {
        return mensaje;
    }

    /**
     * Descripción del hito <b>en español</b>, que es la que se guarda en el timeline.
     *
     * <p>No es un capricho de idioma: {@code FulfillmentService.collectNewSteps} deduplica por
     * «estado|descripción» y {@code ShipmentEventMessage.translate} traduce buscando el texto español.
     * Si aquí se devolviera el inglés de CJ, el correo saldría sin traducir y —lo peor— cualquier cambio
     * de redacción de CJ crearía un hito «nuevo» en cada sondeo: el timeline repetido que ya se corrigió
     * una vez.
     */
    public String descripcion() {
        return mensaje != null ? mensaje.es() : null;
    }

    /**
     * Busca el estado por el texto de CJ, sin distinguir mayúsculas ni espacios sobrantes.
     *
     * @return {@code null} si CJ manda un estado que no está en la tabla — hay que anotarlo, no adivinarlo
     */
    public static CjTrackStatus desde(String trackingStatus) {
        if (trackingStatus == null || trackingStatus.isBlank()) {
            return null;
        }
        String buscado = trackingStatus.trim();
        for (CjTrackStatus estado : values()) {
            if (estado.textoCj.equalsIgnoreCase(buscado)) {
                return estado;
            }
        }
        return null;
    }
}
