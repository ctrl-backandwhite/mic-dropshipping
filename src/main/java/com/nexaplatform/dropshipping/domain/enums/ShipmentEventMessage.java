package com.nexaplatform.dropshipping.domain.enums;

/**
 * Mensajes traducidos de los eventos de seguimiento del envío (Cainiao). El timeline genera las
 * descripciones en español; este enum las traduce para el email de notificación según el idioma del
 * usuario. {@code REGISTERED} es el estado interno de la plataforma (el envío se registra en Cainiao)
 * y NO se notifica al cliente.
 */
public enum ShipmentEventMessage {

    REGISTERED("Envío registrado", "Shipment registered"),
    PICKED_UP("Recogido por el transportista", "Picked up by the carrier"),
    IN_TRANSIT("En tránsito internacional", "In international transit"),
    ARRIVED_COUNTRY("Llegó al país de destino", "Arrived in destination country"),
    OUT_FOR_DELIVERY("En reparto", "Out for delivery"),
    DELIVERED("Entregado al destinatario", "Delivered to the recipient");

    private final String es;
    private final String en;

    ShipmentEventMessage(String es, String en) {
        this.es = es;
        this.en = en;
    }

    public String es() {
        return es;
    }

    public String en() {
        return en;
    }

    /**
     * Traduce la descripción (en español, tal cual la genera el tracking) al idioma pedido. Si no se
     * reconoce, devuelve la descripción original para no perder información.
     */
    public static String translate(String spanishDescription, boolean es) {
        if (spanishDescription == null) {
            return null;
        }
        for (ShipmentEventMessage m : values()) {
            if (m.es.equalsIgnoreCase(spanishDescription.trim())) {
                return es ? m.es : m.en;
            }
        }
        return spanishDescription;
    }
}
