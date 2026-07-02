package com.nexaplatform.dropshipping.domain.enums;

/**
 * Descripciones de los movimientos del wallet, traducidas a los 8 idiomas soportados. Las descripciones se
 * guardan en inglés al crear la transacción; se localizan al LEER (mapper) según el idioma activo. Los
 * marcadores {@code {method}} (método de pago ya localizado) y {@code {order}} (nº de pedido) se sustituyen
 * al renderizar. Mismo patrón que {@link OrderEmailLabel}/{@link InvoiceLabel}.
 */
public enum WalletTxMessage {

    RECHARGE("Recarga con {method}", "Recharge via {method}", "Recarga com {method}", "通过{method}充值",
            "Recharge par {method}", "Aufladung per {method}", "Ricarica con {method}", "Opwaardering via {method}"),
    REFUND("Reembolso del pedido {order}", "Refund for order {order}", "Reembolso do pedido {order}",
            "订单 {order} 的退款", "Remboursement de la commande {order}", "Rückerstattung für Bestellung {order}",
            "Rimborso dell'ordine {order}", "Terugbetaling voor bestelling {order}"),
    ORDER_PAYMENT("Pedido {order}", "Order {order}", "Pedido {order}", "订单 {order}", "Commande {order}",
            "Bestellung {order}", "Ordine {order}", "Bestelling {order}");

    private final String es;
    private final String en;
    private final String pt;
    private final String zh;
    private final String fr;
    private final String de;
    private final String it;
    private final String nl;

    WalletTxMessage(String es, String en, String pt, String zh, String fr, String de, String it, String nl) {
        this.es = es;
        this.en = en;
        this.pt = pt;
        this.zh = zh;
        this.fr = fr;
        this.de = de;
        this.it = it;
        this.nl = nl;
    }

    /** Plantilla traducida para el idioma (2 letras); si no se reconoce, devuelve el español. */
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
