package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * Textos de los emails transaccionales del pedido (despachado, entregado, reembolso, actualización de
 * envío) traducidos a los 8 idiomas soportados. El email se envía en el idioma con el que el usuario
 * navega la web. Mismo patrón que {@link InvoiceLabel}. Los cuerpos llevan los marcadores
 * {@code {order}} y {@code {state}} que se sustituyen al renderizar.
 */
public enum OrderEmailLabel {

    SHIPPED_TITLE("Tu pedido va en camino", "Your order is on its way", "Seu pedido está a caminho",
            "您的订单正在配送中", "Votre commande est en route", "Deine Bestellung ist unterwegs",
            "Il tuo ordine è in arrivo", "Je bestelling is onderweg"),
    SHIPPED_BODY("Tu pedido <strong>{order}</strong> ha sido despachado y está en camino.",
            "Your order <strong>{order}</strong> has been shipped and is on its way.",
            "Seu pedido <strong>{order}</strong> foi despachado e está a caminho.",
            "您的订单 <strong>{order}</strong> 已发货,正在配送途中。",
            "Votre commande <strong>{order}</strong> a été expédiée et est en route.",
            "Deine Bestellung <strong>{order}</strong> wurde versandt und ist unterwegs.",
            "Il tuo ordine <strong>{order}</strong> è stato spedito ed è in arrivo.",
            "Je bestelling <strong>{order}</strong> is verzonden en is onderweg."),
    DELIVERED_TITLE("Tu pedido ha sido entregado", "Your order has been delivered", "Seu pedido foi entregue",
            "您的订单已送达", "Votre commande a été livrée", "Deine Bestellung wurde geliefert",
            "Il tuo ordine è stato consegnato", "Je bestelling is bezorgd"),
    DELIVERED_BODY("Tu pedido <strong>{order}</strong> ha sido entregado. ¡Esperamos que lo disfrutes!",
            "Your order <strong>{order}</strong> has been delivered. We hope you enjoy it!",
            "Seu pedido <strong>{order}</strong> foi entregue. Esperamos que aproveite!",
            "您的订单 <strong>{order}</strong> 已送达。希望您满意!",
            "Votre commande <strong>{order}</strong> a été livrée. Nous espérons qu'elle vous plaira !",
            "Deine Bestellung <strong>{order}</strong> wurde geliefert. Wir hoffen, sie gefällt dir!",
            "Il tuo ordine <strong>{order}</strong> è stato consegnato. Ci auguriamo che ti piaccia!",
            "Je bestelling <strong>{order}</strong> is bezorgd. We hopen dat je ervan geniet!"),
    REFUNDED_TITLE("Reembolso procesado", "Refund processed", "Reembolso processado", "退款已处理",
            "Remboursement traité", "Rückerstattung verarbeitet", "Rimborso elaborato", "Terugbetaling verwerkt"),
    REFUNDED_BODY(
            "Hemos procesado el reembolso de tu pedido <strong>{order}</strong>. El importe se devolverá a tu método de pago original.",
            "We have processed the refund for your order <strong>{order}</strong>. The amount will be returned to your original payment method.",
            "Processámos o reembolso do seu pedido <strong>{order}</strong>. O valor será devolvido ao seu método de pagamento original.",
            "我们已处理您订单 <strong>{order}</strong> 的退款。金额将退回到您的原支付方式。",
            "Nous avons traité le remboursement de votre commande <strong>{order}</strong>. Le montant sera reversé sur votre moyen de paiement initial.",
            "Wir haben die Rückerstattung für deine Bestellung <strong>{order}</strong> bearbeitet. Der Betrag wird auf dein ursprüngliches Zahlungsmittel zurückerstattet.",
            "Abbiamo elaborato il rimborso del tuo ordine <strong>{order}</strong>. L'importo sarà restituito sul metodo di pagamento originale.",
            "We hebben de terugbetaling voor je bestelling <strong>{order}</strong> verwerkt. Het bedrag wordt teruggestort op je oorspronkelijke betaalmethode."),
    TRACK_TITLE("Actualización de tu envío", "Shipment update", "Atualização do seu envio", "您的物流更新",
            "Mise à jour de votre envoi", "Versand-Update", "Aggiornamento della spedizione",
            "Update van je verzending"),
    TRACK_BODY("Tu pedido <strong>{order}</strong> ha cambiado de estado: <strong>{state}</strong>.",
            "Your order <strong>{order}</strong> has a new status: <strong>{state}</strong>.",
            "Seu pedido <strong>{order}</strong> mudou de estado: <strong>{state}</strong>.",
            "您的订单 <strong>{order}</strong> 状态已更新:<strong>{state}</strong>。",
            "Votre commande <strong>{order}</strong> a changé de statut : <strong>{state}</strong>.",
            "Deine Bestellung <strong>{order}</strong> hat einen neuen Status: <strong>{state}</strong>.",
            "Il tuo ordine <strong>{order}</strong> ha un nuovo stato: <strong>{state}</strong>.",
            "Je bestelling <strong>{order}</strong> heeft een nieuwe status: <strong>{state}</strong>."),
    TRACKING_NUMBER("Nº de seguimiento: ", "Tracking number: ", "Nº de rastreio: ", "物流单号:",
            "Numéro de suivi : ", "Sendungsnummer: ", "Numero di tracciamento: ", "Volgnummer: "),
    LOCATION("Ubicación: ", "Location: ", "Localização: ", "位置:", "Emplacement : ", "Standort: ",
            "Posizione: ", "Locatie: "),
    CTA_TRACK("Seguir mi pedido", "Track my order", "Acompanhar meu pedido", "跟踪我的订单",
            "Suivre ma commande", "Bestellung verfolgen", "Segui il mio ordine", "Mijn bestelling volgen"),
    CTA_VIEW_ORDER("Ver pedido", "View order", "Ver pedido", "查看订单", "Voir la commande",
            "Bestellung ansehen", "Vedi ordine", "Bestelling bekijken"),
    AUTO_NOTE("Este es un mensaje automático, no respondas a este correo.",
            "This is an automated message, please do not reply to this email.",
            "Esta é uma mensagem automática, não responda a este e-mail.",
            "这是一封自动邮件,请勿回复。",
            "Ceci est un message automatique, merci de ne pas y répondre.",
            "Dies ist eine automatische Nachricht, bitte antworte nicht auf diese E-Mail.",
            "Questo è un messaggio automatico, non rispondere a questa email.",
            "Dit is een automatisch bericht, beantwoord deze e-mail niet.");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    OrderEmailLabel(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    /** Texto traducido para el idioma (2 letras); si no se reconoce, devuelve el español. */
    public String of(String lang) {
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

    /** Texto con el número de pedido sustituido en {@code {order}}. */
    public String of(String lang, String order) {
        return of(lang).replace("{order}", order != null ? order : "");
    }
}
