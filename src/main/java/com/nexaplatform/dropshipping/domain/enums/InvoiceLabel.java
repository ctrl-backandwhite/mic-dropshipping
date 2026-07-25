package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * Textos de la factura traducidos a los 8 idiomas soportados (es, en, pt, zh, fr, de, it, nl). La
 * factura se emite en el idioma con el que el usuario navega la web. Mismo patrón que
 * {@link PaymentMethodLabel}: el enum es el conjunto de constantes y cada una lleva sus traducciones.
 */
public enum InvoiceLabel {

    INVOICE("Factura", "Invoice", "Fatura", "发票", "Facture", "Rechnung", "Fattura", "Factuur"),
    TITLE_PAID("Pago confirmado", "Payment confirmed", "Pagamento confirmado", "付款已确认",
            "Paiement confirmé", "Zahlung bestätigt", "Pagamento confermato", "Betaling bevestigd"),
    INTRO("Gracias por tu compra. Aquí tienes la factura de tu pedido.",
            "Thank you for your purchase. Here is the invoice for your order.",
            "Obrigado pela sua compra. Aqui está a fatura do seu pedido.",
            "感谢您的购买。这是您订单的发票。",
            "Merci pour votre achat. Voici la facture de votre commande.",
            "Vielen Dank für Ihren Einkauf. Hier ist die Rechnung zu Ihrer Bestellung.",
            "Grazie per il tuo acquisto. Ecco la fattura del tuo ordine.",
            "Bedankt voor je aankoop. Hier is de factuur van je bestelling."),
    BILL_TO("Facturar a", "Bill to", "Faturar para", "账单收件人", "Facturer à", "Rechnung an",
            "Fatturare a", "Factureren aan"),
    ISSUE_DATE("Fecha de emisión", "Issue date", "Data de emissão", "开具日期", "Date d'émission",
            "Ausstellungsdatum", "Data di emissione", "Datum van uitgifte"),
    PAYMENT_METHOD("Método de pago", "Payment method", "Método de pagamento", "支付方式",
            "Mode de paiement", "Zahlungsmethode", "Metodo di pagamento", "Betaalmethode"),
    PAID("Pagada", "Paid", "Paga", "已支付", "Payée", "Bezahlt", "Pagata", "Betaald"),
    PENDING("Pendiente", "Pending", "Pendente", "待处理", "En attente", "Ausstehend", "In sospeso",
            "In behandeling"),
    DESCRIPTION("Descripción", "Description", "Descrição", "描述", "Description", "Beschreibung",
            "Descrizione", "Omschrijving"),
    QTY("Cant.", "Qty", "Qtd.", "数量", "Qté", "Menge", "Qtà", "Aantal"),
    PRICE("Precio", "Price", "Preço", "单价", "Prix", "Preis", "Prezzo", "Prijs"),
    SUBTOTAL("Subtotal", "Subtotal", "Subtotal", "小计", "Sous-total", "Zwischensumme", "Subtotale",
            "Subtotaal"),
    SHIPPING("Envío", "Shipping", "Envio", "运费", "Livraison", "Versand", "Spedizione", "Verzending"),
    DISCOUNT("Descuento", "Discount", "Desconto", "折扣", "Remise", "Rabatt", "Sconto", "Korting"),
    VAT("IVA", "VAT", "IVA", "增值税", "TVA", "MwSt.", "IVA", "btw"),
    TOTAL("Total", "Total", "Total", "总计", "Total", "Gesamt", "Totale", "Totaal"),
    TAX_ID("NIF/CIF", "Tax ID", "NIF", "税号", "N° fiscal", "USt-IdNr.", "P. IVA", "Btw-nr."),
    TAX_ID_PREFIX("CIF: ", "Tax ID: ", "NIF: ", "税号: ", "N° fiscal : ", "USt-IdNr.: ", "P. IVA: ",
            "Btw-nr.: "),
    VERIFY("Verificar factura", "Verify invoice", "Verificar fatura", "验证发票", "Vérifier la facture",
            "Rechnung prüfen", "Verifica fattura", "Factuur verifiëren"),
    VERIFY_NOTE("Documento generado electrónicamente. Escanea el código QR para verificar la autenticidad de esta factura.",
            "Electronically generated document. Scan the QR code to verify the authenticity of this invoice.",
            "Documento gerado eletronicamente. Leia o código QR para verificar a autenticidade desta fatura.",
            "电子生成的文件。扫描二维码以验证此发票的真实性。",
            "Document généré électroniquement. Scannez le code QR pour vérifier l'authenticité de cette facture.",
            "Elektronisch erzeugtes Dokument. Scannen Sie den QR-Code, um die Echtheit dieser Rechnung zu prüfen.",
            "Documento generato elettronicamente. Scansiona il codice QR per verificare l'autenticità di questa fattura.",
            "Elektronisch gegenereerd document. Scan de QR-code om de echtheid van deze factuur te verifiëren."),
    CTA_VIEW("Ver pedido y factura", "View order & invoice", "Ver pedido e fatura", "查看订单和发票",
            "Voir la commande et la facture", "Bestellung & Rechnung ansehen", "Vedi ordine e fattura",
            "Bestelling & factuur bekijken"),
    CTA_DOWNLOAD("Descargar factura (PDF)", "Download invoice (PDF)", "Baixar fatura (PDF)", "下载发票 (PDF)",
            "Télécharger la facture (PDF)", "Rechnung herunterladen (PDF)", "Scarica fattura (PDF)",
            "Factuur downloaden (PDF)"),
    RECEIPT_NOTE("Este es un comprobante de tu pedido. Conserva esta factura.",
            "This is your order receipt. Please keep this invoice.",
            "Este é o comprovativo do seu pedido. Guarde esta fatura.",
            "这是您的订单凭证。请保留此发票。",
            "Ceci est le justificatif de votre commande. Conservez cette facture.",
            "Dies ist Ihr Bestellbeleg. Bitte bewahren Sie diese Rechnung auf.",
            "Questa è la ricevuta del tuo ordine. Conserva questa fattura.",
            "Dit is je bestelbon. Bewaar deze factuur.");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    InvoiceLabel(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    /** Idioma (2 letras) a partir de un locale tipo "es", "en-US", "pt_BR"; por defecto "es". */
    public static String lang(String locale) {
        if (locale == null || locale.isBlank()) {
            return "es";
        }
        return locale.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
    }

    /** Texto traducido para el idioma indicado; si no se reconoce, devuelve el español. */
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
}
