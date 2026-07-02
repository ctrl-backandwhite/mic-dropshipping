package com.nexaplatform.dropshipping.domain.enums;

/**
 * Mensajes traducidos de los eventos de seguimiento del envío (Cainiao). El timeline genera las
 * descripciones en español; este enum las traduce a los 8 idiomas soportados para el email de
 * notificación según el idioma del usuario. {@code REGISTERED} es el estado interno de la plataforma
 * (el envío se registra) y NO se notifica al cliente.
 */
public enum ShipmentEventMessage {

    REGISTERED("Envío registrado", "Shipment registered", "Envio registado", "已登记发货",
            "Expédition enregistrée", "Sendung registriert", "Spedizione registrata", "Zending geregistreerd"),
    PICKED_UP("Recogido por el transportista", "Picked up by the carrier", "Recolhido pela transportadora",
            "承运商已取件", "Pris en charge par le transporteur", "Vom Spediteur abgeholt",
            "Ritirato dal corriere", "Opgehaald door de vervoerder"),
    IN_TRANSIT("En tránsito internacional", "In international transit", "Em trânsito internacional", "国际运输中",
            "En transit international", "Im internationalen Transit", "In transito internazionale",
            "In internationaal transport"),
    ARRIVED_COUNTRY("Llegó al país de destino", "Arrived in destination country", "Chegou ao país de destino",
            "已到达目的地国家", "Arrivé dans le pays de destination", "Im Zielland angekommen",
            "Arrivato nel paese di destinazione", "Aangekomen in land van bestemming"),
    OUT_FOR_DELIVERY("En reparto", "Out for delivery", "Saiu para entrega", "正在派送", "En cours de livraison",
            "In Zustellung", "In consegna", "Onderweg voor bezorging"),
    DELIVERED("Entregado al destinatario", "Delivered to the recipient", "Entregue ao destinatário", "已送达收件人",
            "Livré au destinataire", "An den Empfänger geliefert", "Consegnato al destinatario",
            "Bezorgd bij de ontvanger");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    ShipmentEventMessage(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    public String es() {
        return es;
    }

    private String of(String lang) {
        switch (lang == null ? "es" : lang) {
            case "en":
                return en;
            case "pt":
                return pt;
            case "zh":
                return zh;
            case "fr":
                return fr;
            case "de":
                return de;
            case "it":
                return it;
            case "nl":
                return nl;
            default:
                return es;
        }
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
            if (m.es.equalsIgnoreCase(spanishDescription.trim())) {
                return m.of(lang);
            }
        }
        return spanishDescription;
    }
}
