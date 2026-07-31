package com.nexaplatform.dropshipping.domain.enums;

/**
 * Mensajes traducidos de los eventos de seguimiento del envío (Cainiao). El timeline genera las
 * descripciones en español; este enum las traduce a los 8 idiomas soportados para el email de
 * notificación según el idioma del usuario. {@code REGISTERED} es el estado interno de la plataforma
 * (el envío se registra) y NO se notifica al cliente.
 */
public enum ShipmentEventMessage {

    REGISTERED(new Translations("Envío registrado", "Shipment registered", "Envio registado", "已登记发货",
            "Expédition enregistrée", "Sendung registriert", "Spedizione registrata", "Zending geregistreerd")),
    PICKED_UP(new Translations("Recogido por el transportista", "Picked up by the carrier",
            "Recolhido pela transportadora", "承运商已取件", "Pris en charge par le transporteur",
            "Vom Spediteur abgeholt", "Ritirato dal corriere", "Opgehaald door de vervoerder")),
    IN_TRANSIT(new Translations("En tránsito internacional", "In international transit",
            "Em trânsito internacional", "国际运输中", "En transit international", "Im internationalen Transit",
            "In transito internazionale", "In internationaal transport")),
    ARRIVED_COUNTRY(new Translations("Llegó al país de destino", "Arrived in destination country",
            "Chegou ao país de destino", "已到达目的地国家", "Arrivé dans le pays de destination",
            "Im Zielland angekommen", "Arrivato nel paese di destinazione", "Aangekomen in land van bestemming")),
    OUT_FOR_DELIVERY(new Translations("En reparto", "Out for delivery", "Saiu para entrega", "正在派送",
            "En cours de livraison", "In Zustellung", "In consegna", "Onderweg voor bezorging")),
    DELIVERED(new Translations("Entregado al destinatario", "Delivered to the recipient", "Entregue ao destinatário",
            "已送达收件人", "Livré au destinataire", "An den Empfänger geliefert", "Consegnato al destinatario",
            "Bezorgd bij de ontvanger"));

    /**
     * Las ocho traducciones de un mensaje. Van agrupadas porque como parámetros sueltos del constructor
     * eran ocho String seguidos: colar el texto alemán en el hueco del italiano no da error de
     * compilación y el correo sale en otro idioma sin que nadie se entere.
     */
    private record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        String of(String lang) {
            return switch (lang == null ? "es" : lang) {
                case "en" -> en;
                case "pt" -> pt;
                case "zh" -> zh;
                case "fr" -> fr;
                case "de" -> de;
                case "it" -> it;
                case "nl" -> nl;
                default -> es;
            };
        }
    }

    private final Translations translations;

    ShipmentEventMessage(Translations translations) {
        this.translations = translations;
    }

    public String es() {
        return translations.es();
    }

    /**
     * Traduce la descripción (en español, tal cual la genera el tracking) al idioma indicado (2 letras).
     * Si no se reconoce, devuelve la descripción original para no perder información.
     */
    public static String translate(String spanishDescription, String lang) {
        if (spanishDescription == null) {
            return null;
        }
        for (ShipmentEventMessage m : values()) {
            if (m.translations.es().equalsIgnoreCase(spanishDescription.trim())) {
                return m.translations.of(lang);
            }
        }
        return spanishDescription;
    }
}
