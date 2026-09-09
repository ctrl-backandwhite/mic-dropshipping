package com.nexaplatform.dropshipping.domain.enums;


/**
 * Textos de los emails transaccionales del pedido (despachado, entregado, reembolso, actualización de
 * envío) traducidos a los 8 idiomas soportados. El email se envía en el idioma con el que el usuario
 * navega la web. Mismo patrón que {@link InvoiceLabel}. Los cuerpos llevan los marcadores
 * {@code {order}} y {@code {state}} que se sustituyen al renderizar.
 */
public enum OrderEmailLabel {

    SHIPPED_TITLE(new Translations(
            "Tu pedido va en camino", "Your order is on its way", "Seu pedido está a caminho",
            "您的订单正在配送中", "Votre commande est en route", "Deine Bestellung ist unterwegs",
            "Il tuo ordine è in arrivo", "Je bestelling is onderweg")),
    SHIPPED_BODY(new Translations(
            "Tu pedido <strong>{order}</strong> ha sido despachado y está en camino.",
            "Your order <strong>{order}</strong> has been shipped and is on its way.",
            "Seu pedido <strong>{order}</strong> foi despachado e está a caminho.",
            "您的订单 <strong>{order}</strong> 已发货,正在配送途中。",
            "Votre commande <strong>{order}</strong> a été expédiée et est en route.",
            "Deine Bestellung <strong>{order}</strong> wurde versandt und ist unterwegs.",
            "Il tuo ordine <strong>{order}</strong> è stato spedito ed è in arrivo.",
            "Je bestelling <strong>{order}</strong> is verzonden en is onderweg.")),
    DELIVERED_TITLE(new Translations(
            "Tu pedido ha sido entregado", "Your order has been delivered", "Seu pedido foi entregue",
            "您的订单已送达", "Votre commande a été livrée", "Deine Bestellung wurde geliefert",
            "Il tuo ordine è stato consegnato", "Je bestelling is bezorgd")),
    DELIVERED_BODY(new Translations(
            "Tu pedido <strong>{order}</strong> ha sido entregado. ¡Esperamos que lo disfrutes!",
            "Your order <strong>{order}</strong> has been delivered. We hope you enjoy it!",
            "Seu pedido <strong>{order}</strong> foi entregue. Esperamos que aproveite!",
            "您的订单 <strong>{order}</strong> 已送达。希望您满意!",
            "Votre commande <strong>{order}</strong> a été livrée. Nous espérons qu'elle vous plaira !",
            "Deine Bestellung <strong>{order}</strong> wurde geliefert. Wir hoffen, sie gefällt dir!",
            "Il tuo ordine <strong>{order}</strong> è stato consegnato. Ci auguriamo che ti piaccia!",
            "Je bestelling <strong>{order}</strong> is bezorgd. We hopen dat je ervan geniet!")),
    PLACED_TITLE(new Translations(
            "Hemos recibido tu pedido", "We received your order", "Recebemos o seu pedido", "我们已收到您的订单",
            "Nous avons bien reçu votre commande", "Wir haben deine Bestellung erhalten",
            "Abbiamo ricevuto il tuo ordine", "We hebben je bestelling ontvangen")),
    PLACED_BODY(new Translations(
            "Tu pedido <strong>{order}</strong> está registrado y pendiente de pago. En cuanto se confirme el pago te enviaremos la factura y empezaremos a prepararlo.",
            "Your order <strong>{order}</strong> is registered and awaiting payment. As soon as the payment is confirmed we will send you the invoice and start preparing it.",
            "O seu pedido <strong>{order}</strong> está registado e a aguardar pagamento. Assim que o pagamento for confirmado, enviaremos a fatura e começaremos a prepará-lo.",
            "您的订单 <strong>{order}</strong> 已登记，正在等待付款。付款确认后，我们将向您发送发票并开始备货。",
            "Votre commande <strong>{order}</strong> est enregistrée et en attente de paiement. Dès que le paiement sera confirmé, nous vous enverrons la facture et commencerons à la préparer.",
            "Deine Bestellung <strong>{order}</strong> ist registriert und wartet auf die Zahlung. Sobald die Zahlung bestätigt ist, senden wir dir die Rechnung und beginnen mit der Vorbereitung.",
            "Il tuo ordine <strong>{order}</strong> è registrato e in attesa di pagamento. Non appena il pagamento sarà confermato ti invieremo la fattura e inizieremo a prepararlo.",
            "Je bestelling <strong>{order}</strong> is geregistreerd en wacht op betaling. Zodra de betaling is bevestigd, sturen we je de factuur en beginnen we met de voorbereiding.")),
    /**
     * Aviso IN-APP del pago confirmado. Va sin etiquetas HTML —a diferencia de los cuerpos de correo—
     * porque el buzón de la aplicación pinta texto plano: enseñaría los &lt;strong&gt; tal cual.
     */
    PAID_TITLE(new Translations(
            "Pago confirmado", "Payment confirmed", "Pagamento confirmado", "付款已确认",
            "Paiement confirmé", "Zahlung bestätigt", "Pagamento confermato", "Betaling bevestigd")),
    PAID_BODY(new Translations(
            "Hemos recibido el pago de tu pedido {order}. Ya estamos preparándolo.",
            "We have received the payment for your order {order}. We are already preparing it.",
            "Recebemos o pagamento do seu pedido {order}. Já o estamos a preparar.",
            "我们已收到您订单 {order} 的付款,正在为您备货。",
            "Nous avons reçu le paiement de votre commande {order}. Nous la préparons déjà.",
            "Wir haben die Zahlung für deine Bestellung {order} erhalten. Wir bereiten sie bereits vor.",
            "Abbiamo ricevuto il pagamento del tuo ordine {order}. Lo stiamo già preparando.",
            "We hebben de betaling voor je bestelling {order} ontvangen. We maken hem al klaar.")),
    REFUNDED_TITLE(new Translations(
            "Reembolso procesado", "Refund processed", "Reembolso processado", "退款已处理",
            "Remboursement traité", "Rückerstattung verarbeitet", "Rimborso elaborato", "Terugbetaling verwerkt")),
    REFUNDED_BODY(new Translations(
            
            "Hemos procesado el reembolso de tu pedido <strong>{order}</strong>. A continuación tienes el detalle:",
            "We have processed the refund for your order <strong>{order}</strong>. Here are the details:",
            "Processámos o reembolso do seu pedido <strong>{order}</strong>. Veja os detalhes:",
            "我们已处理您订单 <strong>{order}</strong> 的退款。详情如下:",
            "Nous avons traité le remboursement de votre commande <strong>{order}</strong>. Voici le détail :",
            "Wir haben die Rückerstattung für deine Bestellung <strong>{order}</strong> bearbeitet. Hier sind die Details:",
            "Abbiamo elaborato il rimborso del tuo ordine <strong>{order}</strong>. Ecco i dettagli:",
            "We hebben de terugbetaling voor je bestelling <strong>{order}</strong> verwerkt. Hier zijn de details:")),
    REFUND_L_ORDER(new Translations(
            "Número de pedido", "Order number", "Número do pedido", "订单编号", "Numéro de commande",
            "Bestellnummer", "Numero d'ordine", "Bestelnummer")),
    REFUND_L_DATE(new Translations(
            "Fecha del reembolso", "Refund date", "Data do reembolso", "退款日期", "Date du remboursement",
            "Datum der Rückerstattung", "Data del rimborso", "Datum terugbetaling")),
    REFUND_L_AMOUNT(new Translations(
            "Importe reembolsado", "Refunded amount", "Valor reembolsado", "退款金额", "Montant remboursé",
            "Erstatteter Betrag", "Importo rimborsato", "Terugbetaald bedrag")),
    REFUND_L_ITEMS(new Translations(
            "Artículos", "Items", "Artigos", "商品", "Articles", "Artikel", "Articoli", "Artikelen")),
    REFUND_L_DEST(new Translations(
            "Destino del reembolso", "Refund destination", "Destino do reembolso", "退款去向",
            "Destination du remboursement", "Ziel der Rückerstattung", "Destinazione del rimborso",
            "Bestemming terugbetaling")),
    REFUND_DEST_WALLET(new Translations(
            "Billetera (inmediato)", "Wallet (instant)", "Carteira (imediato)", "钱包(即时到账)",
            "Portefeuille (immédiat)", "Wallet (sofort)", "Portafoglio (immediato)", "Portemonnee (direct)")),
    REFUND_DEST_CARD(new Translations(
            "Tarjeta original (3-5 días hábiles)", "Original card (3–5 business days)",
            "Cartão original (3-5 dias úteis)", "原银行卡(3-5个工作日)", "Carte d'origine (3 à 5 jours ouvrés)",
            "Ursprüngliche Karte (3–5 Werktage)", "Carta originale (3-5 giorni lavorativi)",
            "Oorspronkelijke kaart (3–5 werkdagen)")),
    REFUND_DEST_PAYPAL(new Translations(
            "PayPal (según sus plazos)", "PayPal (per their timelines)", "PayPal (conforme prazos)",
            "PayPal(按其时效)", "PayPal (selon ses délais)", "PayPal (gemäß deren Fristen)",
            "PayPal (secondo i suoi tempi)", "PayPal (volgens hun termijnen)")),
    TRACK_TITLE(new Translations(
            "Actualización de tu envío", "Shipment update", "Atualização do seu envio", "您的物流更新",
            "Mise à jour de votre envoi", "Versand-Update", "Aggiornamento della spedizione",
            "Update van je verzending")),
    TRACK_BODY(new Translations(
            "Tu pedido <strong>{order}</strong> ha cambiado de estado: <strong>{state}</strong>.",
            "Your order <strong>{order}</strong> has a new status: <strong>{state}</strong>.",
            "Seu pedido <strong>{order}</strong> mudou de estado: <strong>{state}</strong>.",
            "您的订单 <strong>{order}</strong> 状态已更新:<strong>{state}</strong>。",
            "Votre commande <strong>{order}</strong> a changé de statut : <strong>{state}</strong>.",
            "Deine Bestellung <strong>{order}</strong> hat einen neuen Status: <strong>{state}</strong>.",
            "Il tuo ordine <strong>{order}</strong> ha un nuovo stato: <strong>{state}</strong>.",
            "Je bestelling <strong>{order}</strong> heeft een nieuwe status: <strong>{state}</strong>.")),
    TRACKING_NUMBER(new Translations(
            "Nº de seguimiento: ", "Tracking number: ", "Nº de rastreio: ", "物流单号:",
            "Numéro de suivi : ", "Sendungsnummer: ", "Numero di tracciamento: ", "Volgnummer: ")),
    LOCATION(new Translations(
            "Ubicación: ", "Location: ", "Localização: ", "位置:", "Emplacement : ", "Standort: ",
            "Posizione: ", "Locatie: ")),
    CTA_TRACK(new Translations(
            "Seguir mi pedido", "Track my order", "Acompanhar meu pedido", "跟踪我的订单",
            "Suivre ma commande", "Bestellung verfolgen", "Segui il mio ordine", "Mijn bestelling volgen")),
    CTA_VIEW_ORDER(new Translations(
            "Ver pedido", "View order", "Ver pedido", "查看订单", "Voir la commande",
            "Bestellung ansehen", "Vedi ordine", "Bestelling bekijken")),
    AUTO_NOTE(new Translations(
            "Este es un mensaje automático, no respondas a este correo.",
            "This is an automated message, please do not reply to this email.",
            "Esta é uma mensagem automática, não responda a este e-mail.",
            "这是一封自动邮件,请勿回复。",
            "Ceci est un message automatique, merci de ne pas y répondre.",
            "Dies ist eine automatische Nachricht, bitte antworte nicht auf diese E-Mail.",
            "Questo è un messaggio automatico, non rispondere a questa email.",
            "Dit is een automatisch bericht, beantwoord deze e-mail niet."));

    private final Translations texts;

    OrderEmailLabel(Translations texts) {
        this.texts = texts;
    }

    /** Texto traducido para el idioma (2 letras); si no se reconoce, devuelve el español. */
    public String of(String lang) {
        return texts.forLanguage(lang);
    }

    /** Texto con el número de pedido sustituido en {@code {order}}. */
    public String of(String lang, String order) {
        return of(lang).replace("{order}", order != null ? order : "");
    }

    /**
     * El mismo texto en los 8 idiomas soportados. Las traducciones son una sola cosa —una etiqueta—, así
     * que viajan agrupadas en un valor de dominio en vez de como 8 parámetros sueltos del constructor
     * del enum (java:S107), y la resolución del idioma vive con los textos en lugar de en el enum.
     */
    public record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        /** Texto del idioma pedido; el español hace de respaldo para cualquier idioma no soportado. */
        public String forLanguage(String lang) {
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
}
