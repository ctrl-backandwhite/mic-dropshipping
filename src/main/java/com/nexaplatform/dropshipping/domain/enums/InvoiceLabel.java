package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;

/**
 * Textos de la factura traducidos a los 8 idiomas soportados (es, en, pt, zh, fr, de, it, nl). La
 * factura se emite en el idioma con el que el usuario navega la web. Mismo patrón que
 * {@link PaymentMethodLabel}: el enum es el conjunto de constantes y cada una lleva sus traducciones.
 */
public enum InvoiceLabel {

    INVOICE(new Translations("Factura", "Invoice", "Fatura", "发票", "Facture", "Rechnung", "Fattura", "Factuur")),
    TITLE_PAID(new Translations("Pago confirmado", "Payment confirmed", "Pagamento confirmado", "付款已确认",
            "Paiement confirmé", "Zahlung bestätigt", "Pagamento confermato", "Betaling bevestigd")),
    INTRO(new Translations("Gracias por tu compra. Aquí tienes la factura de tu pedido.",
            "Thank you for your purchase. Here is the invoice for your order.",
            "Obrigado pela sua compra. Aqui está a fatura do seu pedido.",
            "感谢您的购买。这是您订单的发票。",
            "Merci pour votre achat. Voici la facture de votre commande.",
            "Vielen Dank für Ihren Einkauf. Hier ist die Rechnung zu Ihrer Bestellung.",
            "Grazie per il tuo acquisto. Ecco la fattura del tuo ordine.",
            "Bedankt voor je aankoop. Hier is de factuur van je bestelling.")),
    BILL_TO(new Translations("Facturar a", "Bill to", "Faturar para", "账单收件人", "Facturer à", "Rechnung an",
            "Fatturare a", "Factureren aan")),
    ISSUE_DATE(new Translations("Fecha de emisión", "Issue date", "Data de emissão", "开具日期", "Date d'émission",
            "Ausstellungsdatum", "Data di emissione", "Datum van uitgifte")),
    PAYMENT_METHOD(new Translations("Método de pago", "Payment method", "Método de pagamento", "支付方式",
            "Mode de paiement", "Zahlungsmethode", "Metodo di pagamento", "Betaalmethode")),
    PAID(new Translations("Pagada", "Paid", "Paga", "已支付", "Payée", "Bezahlt", "Pagata", "Betaald")),
    PENDING(new Translations("Pendiente", "Pending", "Pendente", "待处理", "En attente", "Ausstehend", "In sospeso",
            "In behandeling")),
    DESCRIPTION(new Translations("Descripción", "Description", "Descrição", "描述", "Description", "Beschreibung",
            "Descrizione", "Omschrijving")),
    QTY(new Translations("Cant.", "Qty", "Qtd.", "数量", "Qté", "Menge", "Qtà", "Aantal")),
    PRICE(new Translations("Precio", "Price", "Preço", "单价", "Prix", "Preis", "Prezzo", "Prijs")),
    SUBTOTAL(new Translations(Word.SUBTOTAL, Word.SUBTOTAL, Word.SUBTOTAL, "小计", "Sous-total", "Zwischensumme",
            "Subtotale", "Subtotaal")),
    SHIPPING(new Translations("Envío", "Shipping", "Envio", "运费", "Livraison", "Versand", "Spedizione",
            "Verzending")),
    DISCOUNT(new Translations("Descuento", "Discount", "Desconto", "折扣", "Remise", "Rabatt", "Sconto", "Korting")),
    VAT(new Translations("IVA", "VAT", "IVA", "增值税", "TVA", "MwSt.", "IVA", "btw")),
    TOTAL(new Translations(Word.TOTAL, Word.TOTAL, Word.TOTAL, "总计", Word.TOTAL, "Gesamt", "Totale", "Totaal")),
    // El emisor es una sociedad IRLANDESA: lo que se imprime es su número de IVA intracomunitario, no un
    // CIF ni un NIF, que son identificadores españoles y no existen en Irlanda.
    TAX_ID(new Translations("NIF-IVA", "VAT", "NIF-IVA", "增值税号", "N° de TVA", "USt-IdNr.", "P. IVA",
            "Btw-nr.")),
    TAX_ID_PREFIX(new Translations("NIF-IVA: ", "VAT: ", "NIF-IVA: ", "增值税号: ", "N° de TVA : ", "USt-IdNr.: ",
            "P. IVA: ", "Btw-nr.: ")),
    VERIFY(new Translations("Verificar factura", "Verify invoice", "Verificar fatura", "验证发票",
            "Vérifier la facture", "Rechnung prüfen", "Verifica fattura", "Factuur verifiëren")),
    VERIFY_NOTE(new Translations(
            "Documento generado electrónicamente. Escanea el código QR para verificar la autenticidad de esta factura.",
            "Electronically generated document. Scan the QR code to verify the authenticity of this invoice.",
            "Documento gerado eletronicamente. Leia o código QR para verificar a autenticidade desta fatura.",
            "电子生成的文件。扫描二维码以验证此发票的真实性。",
            "Document généré électroniquement. Scannez le code QR pour vérifier l'authenticité de cette facture.",
            "Elektronisch erzeugtes Dokument. Scannen Sie den QR-Code, um die Echtheit dieser Rechnung zu prüfen.",
            "Documento generato elettronicamente. Scansiona il codice QR per verificare l'autenticità di questa fattura.",
            "Elektronisch gegenereerd document. Scan de QR-code om de echtheid van deze factuur te verifiëren.")),
    CTA_VIEW(new Translations("Ver pedido y factura", "View order & invoice", "Ver pedido e fatura", "查看订单和发票",
            "Voir la commande et la facture", "Bestellung & Rechnung ansehen", "Vedi ordine e fattura",
            "Bestelling & factuur bekijken")),
    CTA_DOWNLOAD(new Translations("Descargar factura (PDF)", "Download invoice (PDF)", "Baixar fatura (PDF)",
            "下载发票 (PDF)", "Télécharger la facture (PDF)", "Rechnung herunterladen (PDF)", "Scarica fattura (PDF)",
            "Factuur downloaden (PDF)")),
    RECEIPT_NOTE(new Translations("Este es un comprobante de tu pedido. Conserva esta factura.",
            "This is your order receipt. Please keep this invoice.",
            "Este é o comprovativo do seu pedido. Guarde esta fatura.",
            "这是您的订单凭证。请保留此发票。",
            "Ceci est le justificatif de votre commande. Conservez cette facture.",
            "Dies ist Ihr Bestellbeleg. Bitte bewahren Sie diese Rechnung auf.",
            "Questa è la ricevuta del tuo ordine. Conserva questa fattura.",
            "Dit is je bestelbon. Bewaar deze factuur.")),
    // El art. 16.3 del Reglamento (UE) 2023/988 admite que el operador económico figure "en un documento de
    // acompañamiento". Como el embalaje lo prepara el proveedor y no se controla, la factura ES ese
    // documento: por eso el bloque va aquí y no solo en la ficha.
    EU_RESPONSIBLE(new Translations("Operador económico responsable en la UE",
            "Responsible economic operator in the EU", "Operador económico responsável na UE",
            "欧盟责任经济经营者", "Opérateur économique responsable dans l'UE",
            "Verantwortlicher Wirtschaftsakteur in der EU", "Operatore economico responsabile nell'UE",
            "Verantwoordelijke marktdeelnemer in de EU")),
    EU_RESPONSIBLE_NOTE(new Translations(
            "Datos publicados conforme al artículo 16 del Reglamento (UE) 2023/988.",
            "Information published pursuant to Article 16 of Regulation (EU) 2023/988.",
            "Dados publicados nos termos do artigo 16.º do Regulamento (UE) 2023/988.",
            "根据(欧盟)2023/988号条例第16条公布的信息。",
            "Informations publiées conformément à l'article 16 du règlement (UE) 2023/988.",
            "Angaben gemäß Artikel 16 der Verordnung (EU) 2023/988.",
            "Dati pubblicati ai sensi dell'articolo 16 del regolamento (UE) 2023/988.",
            "Gegevens gepubliceerd overeenkomstig artikel 16 van Verordening (EU) 2023/988."));

    private final Translations translations;

    InvoiceLabel(Translations translations) {
        this.translations = translations;
    }

    /**
     * Palabras que se escriben IGUAL en varios idiomas. Viven en una clase anidada porque una constante del
     * propio enum no puede usarse en la lista de argumentos de sus constantes (referencia adelantada).
     */
    private static final class Word {
        private static final String SUBTOTAL = "Subtotal";
        private static final String TOTAL = "Total";

        private Word() {
        }
    }

    /**
     * Las ocho traducciones de una etiqueta, juntas en un solo valor: así el constructor del enum recibe un
     * parámetro en vez de ocho sueltos, donde cualquier idioma desplazado pasaba inadvertido.
     */
    public record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        /** Texto traducido para el idioma indicado; si no se reconoce, devuelve el español. */
        public String of(String lang) {
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

    /** Idioma (2 letras) a partir de un locale tipo "es", "en-US", "pt_BR"; por defecto "es". */
    public static String lang(String locale) {
        if (locale == null || locale.isBlank()) {
            return "es";
        }
        return locale.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
    }

    /** Texto traducido para el idioma indicado; si no se reconoce, devuelve el español. */
    public String of(String lang) {
        return translations.of(lang);
    }
}
